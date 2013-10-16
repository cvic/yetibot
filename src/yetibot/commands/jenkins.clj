(ns yetibot.commands.jenkins
  (:require [http.async.client :as client]
            [clojure.data.json :as json]
            [clojure.string :as s]
            [robert.hooke :as rh]
            [clj-time.core :as t]
            [clj-time.coerce :as c]
            [clojure.core.cache :as cache]
            [yetibot.hooks :refer [cmd-hook]]
            [yetibot.util.http :refer [fetch get-json with-client]]))


(def base-uri (System/getenv "JENKINS_URI"))
(def auth {:user (System/getenv "JENKINS_USER")
           :password (System/getenv "JENKINS_API_KEY")
           :preemptive true})
(def default-job (System/getenv "JENKINS_DEFAULT_JOB"))

; Helpers
(def cache-ttl (* 1000 60 60))
(def jenkins-cache (atom (cache/ttl-cache-factory {} :ttl cache-ttl)))

(defn fetch-root []
  (let [uri (format "%s/api/json" base-uri)]
    (get-json uri auth)))

(defn root-data []
  (swap! jenkins-cache
         (fn [c]
           (if (cache/has? c :root)
             (cache/hit c :root)
             (cache/miss c :root (fetch-root))))))

(defn job-names [] (map :name (:jobs (:root (root-data)))))



; API Calls
(defn status [job-name]
  (with-open [client (client/create-client)]
    (let [uri (str base-uri "job/" job-name "/lastBuild/api/json")
          response (client/GET client uri :auth auth)]
      (client/await response)
      (let [unparsed (client/string response)]
        (json/read-json unparsed)))))

(defn job-status [job-name]
  "Sends job status info to chat. Sample output:
  SUCCESS at Wed Nov 16. Started by trevor.
  Currently building? false
  [Changeset]"
  (let [json (status job-name)]
    (if-let [building (str (:building json))] ; convert to string so a `false` doesn't give a false-negative
      (let [result (str (:result json))
                   changeset (s/join
                               \newline
                               (map (fn [i]
                                      (let [msg (:msg i)
                                            author (if (seq (:author i))
                                                     (:fullName (:author i)) ; git
                                                     (:user i))]; svn
                                        (str author ": " msg)))
                                    (:items (:changeSet json))))]
        [(:url json)
         (str
           (if result (str result " at "))
           (c/to-date (c/from-long (:timestamp json)))
           ". "
           (:shortDescription (first (:causes (first (:actions json)))))
           ".")
         (str "Currently building: " building)
         ""
         changeset]))))

(defn report-job-url [job-name]
  ; Wait a second before requesting the job url so that Jenkins has time to actually
  ; start the build. Otherwise, the previous build url will be reported instead of
  ; the latest.
  (Thread/sleep 8000)
  (let [json (status job-name)]
    (yetibot.chat/chat-data-structure (:url json))))

(defn build
  "jen build <job-name>"
  [{[_ job-pattern] :match}]
  (let [job-pattern (re-pattern job-pattern)]
    (letfn [(match-job [pattern job] (when (re-find pattern job) job))]
      (if-let [job-name (some (partial match-job job-pattern) (job-names))]
        (let [uri (format "%sjob/%s/build" base-uri job-name)
                  response (fetch uri auth)]
          (future (report-job-url job-name))
          (str "Starting build on " job-name))
        (format "I couldn't match any jobs on  %s. Get it right next time." job-pattern)))))

(defn list-jobs
  ([] (job-names))
  ([n] (take n (job-names))))

(defn list-jobs-matching [match]
  (let [p (re-pattern (s/trim match))]
    (filter #(re-find p %) (job-names))))

(defn build-default-cmd
  "jen build # build default job if configured"
  [_] (if default-job
        (build {:match [nil default-job]})
        "No default job configured"))

(defn status-cmd
  "
jen status <job-name>       # show Jenkins status for <job-name>
jen status                  # show Jenkins status for default job if configured"
  [{args :match}]
  (if (coll? args)
    (job-status (second args))
    (job-status default-job)))

(defn list-cmd
  "
jen list                    # lists all jenkins jobs
jen list <pattern>          # lists jenkins jobs containing <string>"
  [{args :match}]
  (if (coll? args)
    (list-jobs-matching (second args))
    (list-jobs)))

(cmd-hook #"jen"
          #"^build$" build-default-cmd
          #"^build\s(\S+)\s*$" build
          #"^status$" status-cmd
          #"^status\s(.+)" status-cmd
          #"^list\s(.+)" list-cmd
          #"^list\s*$" list-cmd)
