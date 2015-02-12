(ns clj-bugsnag.core
  (:require [clj-stacktrace.core :refer [parse-exception]]
            [clj-stacktrace.repl :refer [method-str]]
            [clojure.java.shell :refer [sh]]
            [clj-http.client :as http]
            [environ.core :refer [env]]
            [clojure.data.json :as json]
            [clojure.repl :as repl]
            [clojure.string :as string]
            [clojure.walk :as walk]))

(defn- transform-stacktrace
  [trace-elems project-ns]
  (vec (for [{:keys [file line ns] :as elem} trace-elems
             :let [project? (.startsWith (or ns "_") project-ns)]]
          {:file file :lineNumber line :method (method-str elem) :inProject project?})))

(defn- stringify
  [thing]
  (if (or (map? thing) (string? thing) (number? thing) (sequential? thing))
    thing
    (str thing)))

(defn- find-source-snippet
  [around, function-name]
  (let [fn-sym (symbol function-name)
        fn-var (find-var fn-sym)
        source (repl/source-fn fn-sym)
        start (-> fn-var meta :line)
        indexed-lines (map-indexed (fn [i, line]
                                      [(+ i start), (string/trimr line)])
                                   (string/split-lines source))]
    (into {} (filter #(<= (- around 3) (first %) (+ around 3)) indexed-lines))))

(defn- source-of-crash-site
  [stacktrace]
  (try
    (let [clj-traces (filter #(.endsWith (:file %) ".clj") stacktrace)
          crash-site (first (concat (filter :inProject clj-traces) clj-traces))]
      (find-source-snippet (:lineNumber crash-site) (:method crash-site)))
    (catch Exception ex
      nil)))

(defn post-data
  [exception data]
  (let [ex (parse-exception exception)
        class-name (.getName (:class ex))
        project-ns (get data :project-ns "\000")
        stacktrace (transform-stacktrace (:trace-elems ex) project-ns)
        base-meta (if-let [d (ex-data exception)]
                    {"ex–data" d}
                    {})]
    {:apiKey (:api-key data (env :bugsnag-key))
     :notifier {:name "clj-bugsnag"
                :version "0.1.2"
                :url "https://github.com/6wunderkinder/clj-bugsnag"}
     :events [{:payloadVersion "2"
               :exceptions [{:errorClass class-name
                             :message (:message ex)
                             :stacktrace stacktrace
                             :code (source-of-crash-site stacktrace)}]
               :context (:context data)
               :groupingHash (or (:group data)
                               (if (isa? (type exception) clojure.lang.ExceptionInfo)
                                 (:message ex)
                                 class-name))
               :severity (or (:severity data) "error")
               :user (:user data)
               :app {:version (string/trim (:out (sh "git" "rev-parse" "HEAD")))
                     :releaseStage (or (:environment data) "production")}
               :device {:hostname (.. java.net.InetAddress getLocalHost getHostName)}
               :metaData (walk/postwalk stringify (merge base-meta (:meta data)))}]}))


(defn notify
  "Main interface for manually reporting exceptions.
   When not :api-key is provided in options,
   tries to load BUGSNAG_KEY var from enviroment."
  ([exception]
    (notify exception nil))
  ([exception, options]
    (let [params (post-data exception options)
          url "https://notify.bugsnag.com/"]
      (http/post url {:form-params params
                      :content-type :json}))))
