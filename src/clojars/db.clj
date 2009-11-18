(ns clojars.db
  (:use clojure.contrib.sql
        clojure.contrib.duck-streams
        [clojure.contrib.str-utils2 :only [join]])
  (:import java.security.MessageDigest
           java.util.Date
           java.io.File))
;;
;; TODO: should move this to a config file
;;
(def db {:classname "org.sqlite.JDBC"
         :subprotocol "sqlite"
         :subname "/home/clojars/data/db"})

(def key-file "/home/clojars/data/auth_keys")
(def ssh-options "no-agent-forwarding,no-port-forwarding,no-pty,no-X11-forwarding")

(def *reserved-names* 
     #{"clojure" "clojars" "clojar" "register" "login"
       "pages" "logout" "password" "username" "user"
       "repo" "repos" "jar" "jars" "about" "help" "doc"
       "docs" "pages" "images" "js" "css" "maven" "api"
       "download" "create" "new" "upload" "contact" "terms"
       "group" "groups" "browse" "status" "blog" "search"
       "email" "welcome" "devel" "development" "test" "testing"
       "prod" "production" "admin" "administrator" "root"
       "webmaster" "profile" "dashboard" "settings" "options"
       "index" "files"})

(defn write-key-file [path]
  (locking key-file
   (let [new-file (File. (str path ".new"))]
     (with-query-results rs ["select user, ssh_key from users"]
       (with-open [f (writer new-file)]
         (doseq [x rs]
           (.println f (str "command=\"ng --nailgun-port 8700 clojars.scp " (:user x) 
                            "\"," ssh-options " " 
                            (.replaceAll (.trim (:ssh_key  x)) 
                                               "[\n\r\0]" ""))))))
     (.renameTo new-file (File. path)))))

(defn db-middleware
  [handler]
  (fn [request]
    (with-connection db (handler request))))

(defmacro with-db
  [& body]
  `(with-connection db
     ~@body))

(defn sha1 [s]
  (when s
    (let [md (MessageDigest/getInstance "SHA")]
      (.update md (.getBytes s))
      (format "%040x" (java.math.BigInteger. 1 (.digest md))))))

(defn find-user [username]
  (with-query-results rs ["select * from users where user = ?" username]
    (first rs)))

(defn find-groups [username]
  (with-query-results rs ["select * from groups where user = ?" username]
    (doall (map :name rs))))

(defn group-members [group]
  (with-query-results rs ["select * from groups where name like ?" group]
    (doall (map :user rs))))

(defn auth-user [user pass]
  (with-query-results rs ["select * from users where (user = ? or
                           email = ?) and password = ?" user 
                           user (sha1 pass)]
    (first rs)))

(defn jars-by-user [user]
  (with-query-results rs [(str "select * from jars where user = ? "
                               "group by group_name, jar_name") user]
    (vec rs)))

(defn jars-by-group [group]
  (with-query-results rs [(str "select * from jars where "
                               "group_name = ? group by jar_name")
                          group]
    (vec rs)))

(defn find-canon-jar [jarname]
  (with-query-results rs 
      [(str "select * from jars where "
            "jar_name = ? and group_name = ?")
       jarname jarname]
    (first rs)))

(defn find-jar 
  ([jarname]
     (with-query-results rs [(str "select * from jars where "
                                  "jar_name = ?") jarname]
       (first rs)))
  ([user jarname]
      (with-query-results rs [(str "select * from jars where user = ? and "
                                   "jar_name = ?") user jarname]
        (first rs))))


(defn add-user [email user password ssh-key]
  (insert-values :users
    [:email :user :password :ssh_key :created]
    [email user (sha1 password) ssh-key (Date.)])
  (insert-values :groups
    [:name :user]
    [(str "org.clojars." user) user])
  (write-key-file key-file))

(defn update-user [account email user password ssh-key]
  (update-values :users ["user=?" account]
   {:email email :user user :password (sha1 password)
    :ssh_key ssh-key})
  (write-key-file key-file))

(defn add-member [group user]
  (insert-records :groups
                  {:name group
                   :user user}))

(defn check-and-add-group [account group jar]
  (when-not (re-matches #"^[a-z0-9-_.]+$" group)
    (throw (Exception. (str "Group names must consist of lowercase "
                            "letters, numbers, hyphens, underscores "
                            "and full-stops."))))
  (let [members (group-members group)]
    (if (empty? members)
      (if (or (find-user group) (*reserved-names* group))
        (throw (Exception. (str "The group name " group " is already taken.")))
        (add-member group account))
      (when-not (some #{account} members)
        (throw (Exception. (str "You don't have access to the "
                                group " group.")))))))

(defn add-jar [account jarmap]
  (when-not (re-matches #"^[a-z0-9-_.]+$" (:name jarmap))
    (throw (Exception. (str "Jar names must consist solely of lowercase "
                            "letters, numbers, hyphens and underscores."))))
  
  (with-connection db
    (transaction
     (check-and-add-group account (:group jarmap) (:name jarmap))
     (insert-records
      :jars
      {:group_name (:group jarmap)
       :jar_name   (:name jarmap)
       :version    (:version jarmap)
       :user       account
       :created    (Date.)
       :description (:description jarmap)
       :homepage   (:homepage jarmap)
       :authors    (join ", " (map #(.replace % "," "") 
                                   (:authors jarmap)))}))))

(defn search-jars [query & [offset]]
  ;; TODO make less stupid ;-)
  (with-query-results rs
      [(str "select * from jars where "
            "description match ? or "
            "jar_name like ?"
;            "group_name match ? or "
;            "user match ? "
;            "group by jar_name, group_name"
            "limit 20 "
            "offset ?")
       query query; query query
       (or offset 0)]
    (vec rs)))

(comment
  (alter-meta clojars.db)

  (with-connection db (add-jar "atotx" {:name "test" :group "test" :version "1.0"
                                      :description "An awesome and non-existent test jar."
                                      :homepage "http://clojars.org/"
                                      :authors ["Alex Osborne" 
                                                "a little fish"]}))
  (with-connection db (find-user "atotx"))
)
