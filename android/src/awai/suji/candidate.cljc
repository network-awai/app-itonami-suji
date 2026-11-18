(ns awai.suji.candidate
  "One task format and one result format shared by the suji-posture bot and the
  Awai Android app.

  The bot (awai.suji.host) validates a posture task, solves it with
  cloud-itonami/suji through the app's own `kami.app-suji.core/solved`, and
  writes a candidate. The app opens that file, solves the same task on the
  device with the same code, and accepts the candidate only when every number
  is equal. Nothing here sends anything anywhere.

  A task names the body, every posture angle the app has a slider for, and the
  session length. A missing angle is an error, not a zero: the bot must say
  what posture it computed."
  (:require [clojure.string :as str]
            [kami.app-suji.core :as core]))

(def task-schema "awai.suji.task.v1")
(def candidate-schema "awai.suji.candidate.v1")
(def candidate-status "candidate-not-reviewed")
(def candidate-max-chars 2000000)

(def ^:private section-key
  {:body "body" :posture "posture" :session-minutes "session_minutes"})

(defn- field-name [k] (str/replace (name k) "-" "_"))

(def fields
  "Every task field, from the app's slider table: the JSON path, the state path
  and the allowed range. The bot can ask for exactly what the app can show."
  (vec (for [{:keys [path min max unit label]} core/controls]
         {:json (if (= 1 (count path))
                  [(section-key (first path))]
                  [(section-key (first path)) (field-name (second path))])
          :path path :min min :max max :unit unit :label label})))

(defn- finite-number? [v]
  #?(:clj (and (number? v) (not (Double/isNaN (double v))) (not (Double/isInfinite (double v))))
     :cljs (and (number? v) (js/isFinite v))))

(defn- json-get [m ks] (reduce (fn [acc k] (when (map? acc) (get acc k))) m ks))

(defn task-problems
  "Problems with a task parsed from JSON (string keys), or [] when it is valid.
  Unknown fields are refused so a misspelt angle cannot silently become the
  default."
  [task]
  (if-not (map? task)
    ["task is not an object"]
    (let [known-top #{"schema" "body" "posture" "session_minutes" "note"}
          known {"body" (set (for [f fields :when (= "body" (first (:json f)))] (second (:json f))))
                 "posture" (set (for [f fields :when (= "posture" (first (:json f)))] (second (:json f))))}]
      (vec
       (concat
        (when-not (= task-schema (get task "schema")) [(str "schema must be " task-schema)])
        (for [k (keys task) :when (not (known-top k))] (str "unknown field " k))
        (for [[section allowed] known
              :let [m (get task section)]
              :when (map? m)
              k (keys m) :when (not (allowed k))]
          (str "unknown field " section "." k))
        (for [section ["body" "posture"] :when (not (map? (get task section)))]
          (str section " must be an object"))
        (when (and (contains? task "note") (not (string? (get task "note")))) ["note must be a string"])
        (for [{:keys [json min max]} fields
              :let [v (json-get task json) where (str/join "." json)]
              :when (or (not (finite-number? v)) (< v min) (> v max))]
          (if (finite-number? v)
            (str where " must be between " min " and " max)
            (str where " is missing or not a number"))))))))

(defn task->state
  "The app state the task describes. Call only on a valid task."
  [task]
  (reduce (fn [state {:keys [json path]}] (assoc-in state path (double (json-get task json))))
          (dissoc core/initial-state :backend)
          fields))

(defn example-task
  "The simulator's opening state as a task, so a bot starts from a posture the
  app also shows."
  []
  (reduce (fn [t {:keys [json path]}] (assoc-in t json (get-in core/initial-state path)))
          {"schema" task-schema "note" "シミュレータの初期姿勢（膝の上のノート PC）"}
          fields))

(defn- jsonable
  "The solved answer as plain JSON data with sorted keys, so two runs of the
  same code serialize identically. Keywords become their full names; a
  non-finite number becomes the string it prints as, so it is not lost to null."
  [x]
  (cond
    (map? x) (into (sorted-map) (for [[k v] x] [(if (keyword? k) (subs (str k) 1) (str k)) (jsonable v)]))
    (or (sequential? x) (set? x)) (mapv jsonable (if (set? x) (sort-by str x) x))
    (keyword? x) (subs (str x) 1)
    (number? x) (if (finite-number? x) x (str x))
    (or (string? x) (boolean? x) (nil? x)) x
    :else (str x)))

(defn results
  "Everything the model computes for the task except the render scene: loads,
  muscle tensions and session strain."
  [task]
  (let [{:keys [loads tensions strains]} (core/solved (task->state task))]
    (jsonable {:loads loads :tensions tensions :strains strains})))

(def headline-fields
  "The numbers shown first, as the simulator shows them: neck compression. Read
  from the full results, not computed separately."
  [{:path ["loads" "cervical" "compressive-load-kgf"] :label "圧縮荷重" :unit "kgf" :digits 1}
   {:path ["loads" "cervical" "multiplier-vs-head"] :label "頭部重量比" :unit "×" :digits 1}
   {:path ["loads" "cervical" "compressive-load-n"] :label "同 (N)" :unit "N" :digits 0}])

(defn headline [res]
  (into {} (for [{:keys [path]} headline-fields] [(str/join "." path) (get-in res path)])))

(defn candidate
  "The candidate the bot writes for a valid task."
  [task suji-pin]
  {"schema" candidate-schema
   "status" candidate-status
   "task" task
   "suji" suji-pin
   "results" (results task)
   "limits" ["A mechanical model of posture load, not a clinical finding."
             "Unreviewed: nothing is published or stored by this file."]})

(def tolerance
  "Two runtimes agree on a number when they differ by at most this, relative to
  the larger magnitude (and absolutely for values near zero).

  Exact equality does not survive a change of JavaScript engine: Math.pow,
  Math.exp and the like are not required to round identically, and suji's
  iterative solvers carry the last-bit difference forward. Measured 2026-11-18
  (AWAI-3512) on the laptop-on-lap task: Node 26 (V8 14.6) against Chrome 156
  (desktop and Android WebView), 135 of 869 numbers differed, the largest by
  5.2e-13 absolute and 1.9e-13 relative (residuals of order 1e-11 differed by
  2.8e-14). 1e-11 leaves a factor of about 20 above that, and still refuses a
  change of 1e-9 to any value smaller than 100 in magnitude."
  1e-11)

(defn- close? [x y]
  (<= (#?(:clj Math/abs :cljs js/Math.abs) (- x y))
      (* tolerance (max 1.0 (#?(:clj Math/abs :cljs js/Math.abs) x) (#?(:clj Math/abs :cljs js/Math.abs) y)))))

(defn first-difference
  "Path to the first value where two result trees disagree: a number outside
  `tolerance`, or anything else not exactly equal (keys, strings, booleans,
  list lengths). nil when they agree."
  ([a b] (first-difference a b []))
  ([a b path]
   (cond
     (and (number? a) (number? b)) (when-not (close? a b) path)
     (and (map? a) (map? b))
     (some (fn [k] (if (and (contains? a k) (contains? b k))
                     (first-difference (get a k) (get b k) (conj path k))
                     (conj path k)))
           (sort (distinct (concat (keys a) (keys b)))))
     (and (vector? a) (vector? b))
     (if (= (count a) (count b))
       (some (fn [i] (first-difference (nth a i) (nth b i) (conj path i))) (range (count a)))
       path)
     (= a b) nil
     :else path)))

(defn max-difference
  "The largest relative difference between two agreeing result trees, for the
  page to state how close the device came."
  [a b]
  (cond
    (and (number? a) (number? b))
    (let [d (#?(:clj Math/abs :cljs js/Math.abs) (- a b))]
      (/ d (max 1.0 (#?(:clj Math/abs :cljs js/Math.abs) a) (#?(:clj Math/abs :cljs js/Math.abs) b))))
    (and (map? a) (map? b)) (reduce max 0 (for [[k v] a] (max-difference v (get b k))))
    (and (vector? a) (vector? b)) (reduce max 0 (map max-difference a b))
    :else 0))

(defn candidate-problems
  "Problems with a candidate parsed from JSON, or [] when it is valid and every
  result agrees, within `tolerance`, with what this runtime computes for its
  task."
  [c]
  (cond
    (not (map? c)) ["candidate is not an object"]
    :else
    (let [shape (vec (concat
                      (when-not (= candidate-schema (get c "schema")) [(str "schema must be " candidate-schema)])
                      (when-not (= candidate-status (get c "status")) [(str "status must be " candidate-status)])
                      (when-not (map? (get c "results")) ["results must be an object"])))
          tp (task-problems (get c "task"))]
      (cond
        (seq shape) shape
        (seq tp) (mapv #(str "task: " %) tp)
        (first-difference (results (get c "task")) (get c "results")) ["results do not recompute on this device"]
        :else []))))
