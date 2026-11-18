(ns awai.suji.host
  "Host command for the suji-posture Itonami profile. Solves one posture task on
  this machine and writes an unreviewed candidate. It edits nothing in the
  repository, publishes nothing and makes no network request.

    node android/.build/host/host.js run <task.json> <out-dir>

    node android/.build/host/host.js example   # the simulator's opening posture as a task

  Exit 0 = candidate written, 2 = task refused (refusal.json written),
  1 = error, 64 = usage."
  (:require [awai.suji.candidate :as c]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(goog-define SUJI_COMMIT "unknown")

(defn suji-pin [] {"repository" "cloud-itonami/suji" "commit" SUJI_COMMIT})

(defn- write-json! [file data]
  (fs/writeFileSync file (str (js/JSON.stringify (clj->js data) nil 2) "\n")))

(defn run-task! [task-path out-dir]
  (let [task (js->clj (js/JSON.parse (fs/readFileSync task-path "utf8")))
        problems (c/task-problems task)]
    (fs/mkdirSync out-dir #js {:recursive true})
    (if (seq problems)
      (do (write-json! (path/join out-dir "refusal.json")
                       {"schema" "awai.suji.refusal.v1" "task" task "problems" problems})
          (doseq [p problems] (js/console.error (str "REFUSED " p)))
          2)
      (let [cand (c/candidate task (suji-pin))
            file (path/join out-dir "candidate.json")]
        (write-json! file cand)
        (println (str "SUJI " SUJI_COMMIT))
        (doseq [[k v] (c/headline (get cand "results"))]
          (println (str "HEADLINE " k " " (js/JSON.stringify (clj->js v)))))
        (println (str "WROTE " file))
        0))))

(defn main [& args]
  (let [[cmd a b] args]
    (set! (.-exitCode js/process)
          (cond
            (and (= cmd "run") a b)
            (try (run-task! a b) (catch :default e (js/console.error (str "ERROR " (.-message e))) 1))
            (= cmd "example")
            (do (println (js/JSON.stringify (clj->js (c/example-task)) nil 2)) 0)
            :else
            (do (js/console.error "usage: host.js run <task.json> <out-dir> | host.js example") 64)))))
