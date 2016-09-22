(ns metabase.models.permissions
  (:require [clojure.data :as data]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [medley.core :as m]
            [schema.core :as s]
            [metabase.api.common :refer [*current-user-id*]]
            [metabase.db :as db]
            (metabase.models [interface :as i]
                             [permissions-group :as groups]
                             [permissions-revision :refer [PermissionsRevision] :as perms-revision])
            [metabase.util :as u]
            [metabase.util.honeysql-extensions :as hx]))


;;; +------------------------------------------------------------------------------------------------------------------------------------------------------+
;;; |                                                                      UTIL FNS                                                                        |
;;; +------------------------------------------------------------------------------------------------------------------------------------------------------+

;;; ---------------------------------------- Dynamic Vars ----------------------------------------

(def ^:dynamic ^Boolean *allow-root-entries*
  "Show we allow permissions entries like `/`? By default, this is disallowed, but you can temporarily disable it here when creating the default entry for `Admin`."
  false)

(def ^:dynamic ^Boolean *allow-admin-permissions-changes*
  "Show we allow changes to be made to permissions belonging to the Admin group? By default this is disabled to prevent accidental tragedy, but you can enable it here
   when creating the default entry for `Admin`."
  false)


;;; ---------------------------------------- Validation ----------------------------------------

(def ^:private ^:const valid-object-path-patterns
  [#"^/db/(\d+)/$"                                ; permissions for the entire DB
   #"^/db/(\d+)/native/$"                         ; permissions for native queries for the DB
   #"^/db/(\d+)/schema/$"                         ; permissions for all schemas in the DB
   #"^/db/(\d+)/schema/([^\\/]*)/$"               ; permissions for a specific schema
   #"^/db/(\d+)/schema/([^\\/]*)/table/(\d+)/$"]) ; permissions for a specific table

(defn valid-object-path?
  "Does OBJECT-PATH follow a known, allowed format?"
  ^Boolean [^String object-path]
  (boolean (when (seq object-path)
             (some (u/rpartial re-matches object-path)
                   valid-object-path-patterns))))


(defn- assert-not-admin-group
  "Check to make sure the `:group_id` for PERMISSIONS entry isn't the admin group."
  [{:keys [group_id]}]
  (when (and (= group_id (:id (groups/admin)))
             (not *allow-admin-permissions-changes*))
    (throw (ex-info "You cannot create or revoke permissions for the 'Admin' group."
             {:status-code 400}))))

(defn- assert-valid-object
  "Check to make sure the value of `:object` for PERMISSIONS entry is valid."
  [{:keys [object]}]
  (when (and object
             (not (valid-object-path? object))
             (or (not= object "/")
                 (not *allow-root-entries*)))
    (throw (ex-info (format "Invalid permissions object path: '%s'." object)
             {:status-code 400}))))

(defn- assert-valid
  "Check to make sure this PERMISSIONS entry is something that's allowed to be saved (i.e. it has a valid `:object` path and it's not for the admin group)."
  [permissions]
  (assert-not-admin-group permissions)
  (assert-valid-object permissions))

;;; ---------------------------------------- Path Util Fns ----------------------------------------

(defn object-path
  "Return the permissions path for a database, schema, or table."
  (^String [database-id]                      (str "/db/" database-id "/"))
  (^String [database-id schema-name]          (str (object-path database-id) "schema/" schema-name "/"))
  (^String [database-id schema-name table-id] (str (object-path database-id schema-name) "table/" table-id "/" )))

(defn native-path
  "Return the native query permissions path for a database."
  ^String [database-id]
  (str (object-path database-id) "native/"))

(defn all-schemas-path
  "Return the permissions path for a database that grants full access to all schemas."
  ^String [database-id]
  (str (object-path database-id) "schema/"))


;;; +------------------------------------------------------------------------------------------------------------------------------------------------------+
;;; |                                                                 ENTITY + LIFECYCLE                                                                   |
;;; +------------------------------------------------------------------------------------------------------------------------------------------------------+

(i/defentity Permissions :permissions)

(defn- pre-insert [permissions]
  (u/prog1 permissions
    (assert-valid permissions)))

(defn- pre-update [permissions]
  (u/prog1 permissions
    (assert-valid permissions)))

(defn- pre-cascade-delete [permissions]
  (assert-not-admin-group permissions))


(u/strict-extend (class Permissions)
  i/IEntity (merge i/IEntityDefaults
                   {:pre-insert         pre-insert
                    :pre-update         pre-update
                    :pre-cascade-delete pre-cascade-delete}))


;;; +------------------------------------------------------------------------------------------------------------------------------------------------------+
;;; |                                                                     GRAPH SCHEMA                                                                     |
;;; +------------------------------------------------------------------------------------------------------------------------------------------------------+

(def ^:private TablePermissionsGraph
  (s/enum :none :all))

(def ^:private SchemaPermissionsGraph
  (s/cond-pre (s/enum :none :all)
              {s/Int TablePermissionsGraph}))

(def ^:private NativePermissionsGraph
  (s/enum :write :read))

(def ^:private DBPermissionsGraph
  {(s/optional-key :native)  NativePermissionsGraph
   (s/optional-key :schemas) (s/cond-pre (s/enum :none :all)
                                         {(s/maybe s/Str) SchemaPermissionsGraph})})

(def ^:private GroupPermissionsGraph
  {s/Int DBPermissionsGraph})

(def ^:private PermissionsGraph
  {:revision s/Int
   :groups   {s/Int GroupPermissionsGraph}})


;;; +------------------------------------------------------------------------------------------------------------------------------------------------------+
;;; |                                                                     GRAPH FETCH                                                                      |
;;; +------------------------------------------------------------------------------------------------------------------------------------------------------+

(defn- is-permissions-for-object?
  "Does PERMISSIONS-PATH grant *full* access for PATH?"
  [path permissions-path]
  (str/starts-with? path permissions-path))

(defn- is-partial-permissions-for-object?
  "Does PERMISSIONS-PATH grant access for a descendant of PATH?"
  [path permissions-path]
  (str/starts-with? permissions-path path))

(defn- permissions-for-path
  "Given a PERMISSIONS-SET of all allowed permissions paths for a Group, return the corresponding permissions status for an object with PATH."
  [permissions-set path]
  (u/prog1 (cond
             (some (partial is-permissions-for-object? path) permissions-set)         :all
             (some (partial is-partial-permissions-for-object? path) permissions-set) :some
             :else                                                                    :none)))

(defn- table->db-object-path     [table] (object-path (:db_id table)))
(defn- table->native-path        [table] (native-path (:db_id table)))
(defn- table->schema-object-path [table] (object-path (:db_id table) (:schema table)))
(defn- table->table-object-path  [table] (object-path (:db_id table) (:schema table) (:id table)))
(defn- table->all-schemas-path   [table] (all-schemas-path (:db_id table)))


(s/defn ^:private schema-graph :- SchemaPermissionsGraph [permissions-set tables]
  (case (permissions-for-path permissions-set (table->schema-object-path (first tables)))
    :all  :all
    :none :none
    :some (into {} (for [table tables]
                     {(:id table) (permissions-for-path permissions-set (table->table-object-path table))}))))

(s/defn ^:private db-graph :- DBPermissionsGraph [permissions-set tables]
  {:native  (case (permissions-for-path permissions-set (table->native-path (first tables)))
              :all  :write
              :none :read)
   :schemas (case (permissions-for-path permissions-set (table->all-schemas-path (first tables)))
              :all  :all
              :none :none
              (m/map-vals (partial schema-graph permissions-set)
                          (group-by :schema tables)))})

(s/defn ^:private group-graph :- GroupPermissionsGraph [permissions-set tables]
  (m/map-vals (partial db-graph permissions-set)
              tables))

;; TODO - if a DB has no tables, then it won't show up in the permissions graph!
(s/defn ^:always-validate graph :- PermissionsGraph
  "Fetch a graph representing the current permissions status for every group and all permissioned objects."
  []
  (let [permissions (db/select [Permissions :group_id :object])
        tables      (group-by :db_id (db/select ['Table :schema :id :db_id]))]
    {:revision (perms-revision/latest-id)
     :groups   (into {} (for [group-id (db/select-ids 'PermissionsGroup)]
                          (let [group-permissions-set (set (for [perms permissions
                                                                 :when (= (:group_id perms) group-id)]
                                                             (:object perms)))]
                            {group-id (group-graph group-permissions-set tables)})))}))




;;; +------------------------------------------------------------------------------------------------------------------------------------------------------+
;;; |                                                                     GRAPH UPDATE                                                                     |
;;; +------------------------------------------------------------------------------------------------------------------------------------------------------+

;;; ---------------------------------------- Helper Fns ----------------------------------------

(defn- delete-related-permissions!
  "Delete all permissions for Group with GROUP-ID for ancestors or descendant objects of object with PATH.
   You can optionally include OTHER-CONDITIONS, which are anded into the filter clause, to further restrict what is deleted."
  {:style/indent 2}
  [group-id path & other-conditions]
  (db/cascade-delete! Permissions
    {:where (apply list
                   :and
                   [:= :group_id group-id]
                   [:or
                    [:like path (hx/concat :object (hx/literal "%"))]
                    [:like :object (str path "%")]]
                   other-conditions)}))

(defn- revoke-permissions!
  "Revoke permissions for Group with GROUP-ID to object with PATH-COMPONENTS."
  [group-id & path-components]
  (delete-related-permissions! group-id (apply object-path path-components)))

(defn- grant-permissions!
  "Grant permissions to Group with GROUP-ID to an object."
  ([group-id db-id schema & more]
   (grant-permissions! group-id (apply object-path db-id schema more)))
  ([group-id path]
   (try
     (db/insert! Permissions
       :group_id group-id
       :object   path)
     ;; on some occasions through weirdness we might accidentally try to insert a key that's already been inserted
     (catch Throwable e
       (log/error (u/format-color 'red "Failed to grant permissions for group %d to '%s': %s" group-id path (.getMessage e)))))))


(defn- revoke-native-permissions! [group-id database-id] (delete-related-permissions! group-id (native-path database-id)))
(defn- grant-native-permissions!  [group-id database-id] (grant-permissions!          group-id (native-path database-id)))


(defn- revoke-db-permissions!
  "Remove all permissions entires for a DB and any child objects.
   This does *not* revoke native permissions; use `revoke-native-permssions!` to do that."
  [group-id database-id]
  (delete-related-permissions! group-id (object-path database-id)
    [:not= :object (native-path database-id)]))

(defn- grant-full-db-permissions!
  "Grant full permissions for all schemas belonging to this database.
   This does *not* grant native permissions; use `grant-native-permissions!` to do that."
  [group-id database-id]
  (grant-permissions! group-id (all-schemas-path database-id)))


;;; ---------------------------------------- Graph Updating Fns ----------------------------------------

(s/defn ^:private ^:always-validate update-table-perms! [group-id :- s/Int, db-id :- s/Int, schema :- s/Str, table-id :- s/Int, new-table-perms :- SchemaPermissionsGraph]
  (case new-table-perms
    :all  (grant-permissions! group-id db-id schema table-id)
    :none (revoke-permissions! group-id db-id schema table-id)))

(s/defn ^:private ^:always-validate update-schema-perms! [group-id :- s/Int, db-id :- s/Int, schema :- s/Str, new-schema-perms :- SchemaPermissionsGraph]
  (cond
    (= new-schema-perms :all)  (grant-permissions! group-id db-id schema)
    (= new-schema-perms :none) (revoke-permissions! group-id db-id schema)
    (map? new-schema-perms)    (doseq [[table-id table-perms] new-schema-perms]
                                 (update-table-perms! group-id db-id schema table-id table-perms))))

(s/defn ^:private ^:always-validate update-native-permissions! [group-id :- s/Int, db-id :- s/Int, new-native-perms :- NativePermissionsGraph]
  (case new-native-perms
    :write (grant-native-permissions! group-id db-id)
    :read  (revoke-native-permissions! group-id db-id)))


(s/defn ^:private ^:always-validate update-db-permissions! [group-id :- s/Int, db-id :- s/Int, new-db-perms :- DBPermissionsGraph]
  (when-let [new-native-perms (:native new-db-perms)]
    (update-native-permissions! group-id db-id new-native-perms))
  (when-let [schemas (:schemas new-db-perms)]
    (cond
      (= schemas :all)  (grant-full-db-permissions! group-id db-id)
      (= schemas :none) (revoke-db-permissions! group-id db-id)
      (map? schemas)    (doseq [schema (keys schemas)]
                          (update-schema-perms! group-id db-id schema (get-in new-db-perms [:schemas schema]))))))

(s/defn ^:private ^:always-validate update-group-permissions! [group-id :- s/Int, new-group-perms :- GroupPermissionsGraph]
  (doseq [db-id (keys new-group-perms)]
    (update-db-permissions! group-id db-id (get new-group-perms db-id))))


(defn- check-revision-numbers
  "Check that the revision number coming in as part of NEW-GRAPH matches the one from OLD-GRAPH.
   This way we can make sure people don't submit a new graph based on something out of date,
   which would otherwise stomp over changes made in the interim.
   Return a 409 (Conflict) if the numbers don't match up."
  [old-graph new-graph]
  (when (not= (:revision old-graph) (:revision new-graph))
    (throw (ex-info "Looks like someone else edited the permissions and your data is out of date. Please fetch new data and try again."
             {:status-code 409}))))

(defn- save-perms-revision!
  "Save changes made to the permissions graph for logging/auditing purposes.
   This doesn't do anything if `*current-user-id*` is unset (e.g. for testing or REPL usage)."
  [current-revision old new]
  (when *current-user-id*
    (db/insert! PermissionsRevision
      :id     (inc current-revision) ; manually specify ID here so if one was somehow inserted in the meantime in the fraction of a second
      :before  old                   ; since we called `check-revision-numbers` the PK constraint will fail and the transaction will abort
      :after   new
      :user_id *current-user-id*)))


(s/defn ^:always-validate update-graph!
  "Update the permissions graph, making any changes neccesary to make it match NEW-GRAPH.
   This should take in a graph that is exactly the same as the one obtained by `graph` with any changes made as needed. The graph is revisioned,
   so if it has been updated by a third party since you fetched it this function will fail and return a 409 (Conflict) exception.
   If nothing needs to be done, this function returns `nil`; otherwise it returns the newly created `PermissionsRevision` entry."
  [new-graph :- PermissionsGraph]
  (let [old-graph (graph)
        [old new] (data/diff (:groups old-graph) (:groups new-graph))]
    (when (or (seq old) (seq new))
      (log/info (format "Updating permissions! 🔏\nOLD:\n%s\nNEW:\n%s"
                        (u/pprint-to-str 'magenta old)
                        (u/pprint-to-str 'blue new)))
      (check-revision-numbers old-graph new-graph)
      (db/transaction
        (doseq [group-id (keys new)]
          (update-group-permissions! group-id (get new group-id)))
        (save-perms-revision! (:revision old-graph) old new)))))
