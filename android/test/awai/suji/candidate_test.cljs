(ns awai.suji.candidate-test
  (:require [awai.suji.candidate :as c]
            [cljs.test :refer [deftest is testing]]))

(def pin {"repository" "cloud-itonami/suji" "commit" "test"})

(defn- json-roundtrip [x] (js->clj (js/JSON.parse (js/JSON.stringify (clj->js x)))))

(deftest every-slider-is-a-task-field
  (is (= (count c/fields) 15))
  (is (empty? (c/task-problems (c/example-task)))))

(deftest a-candidate-recomputes-after-a-json-roundtrip
  (let [cand (json-roundtrip (c/candidate (c/example-task) pin))]
    (is (empty? (c/candidate-problems cand)))
    (is (number? (get-in cand ["results" "loads" "cervical" "compressive-load-kgf"])))))

(deftest a-changed-number-is-refused-and-located
  (let [cand (json-roundtrip (c/candidate (c/example-task) pin))
        path ["results" "loads" "cervical" "compressive-load-kgf"]
        bad (update-in cand path + 1e-9)]
    (is (= ["results do not recompute on this device"] (c/candidate-problems bad)))
    (is (= ["loads" "cervical" "compressive-load-kgf"]
           (c/first-difference (c/results (get bad "task")) (get bad "results"))))))

(deftest a-changed-task-is-refused-even-with-the-old-results
  ;; The results were computed for the original posture; editing the task
  ;; without recomputing must not pass.
  (let [cand (json-roundtrip (c/candidate (c/example-task) pin))
        bad (assoc-in cand ["task" "posture" "head_flexion_deg"] 10)]
    (is (= ["results do not recompute on this device"] (c/candidate-problems bad)))))

(deftest removed-results-are-refused
  (let [cand (json-roundtrip (c/candidate (c/example-task) pin))]
    (is (seq (c/candidate-problems (update-in cand ["results" "loads"] dissoc "cervical"))))
    (is (seq (c/candidate-problems (dissoc cand "results"))))))

(deftest schema-and-status-are-checked
  (let [cand (json-roundtrip (c/candidate (c/example-task) pin))]
    (is (seq (c/candidate-problems (assoc cand "schema" "awai.suji.candidate.v0"))))
    (is (seq (c/candidate-problems (assoc cand "status" "reviewed"))))
    (is (seq (c/candidate-problems "not an object")))))

(deftest task-validation
  (let [t (c/example-task)]
    (testing "a missing angle is an error, not a zero"
      (is (some #(re-find #"posture.head_flexion_deg is missing" %)
                (c/task-problems (update t "posture" dissoc "head_flexion_deg")))))
    (testing "out of the slider's range"
      (is (some #(re-find #"between 0 and 60" %)
                (c/task-problems (assoc-in t ["posture" "head_flexion_deg"] 61)))))
    (testing "a misspelt field is refused"
      (is (some #(re-find #"unknown field posture.head_flex_deg" %)
                (c/task-problems (assoc-in t ["posture" "head_flex_deg"] 10)))))
    (testing "non-numbers"
      (is (seq (c/task-problems (assoc-in t ["body" "total_mass_kg"] "70"))))
      (is (seq (c/task-problems (assoc-in t ["body" "total_mass_kg"] js/NaN)))))
    (testing "schema"
      (is (seq (c/task-problems (assoc t "schema" "awai.suji.task.v2")))))))

(deftest the-task-reaches-the-model
  ;; Moving one angle in the task moves the answer: the task is not ignored.
  (let [a (c/results (c/example-task))
        b (c/results (assoc-in (c/example-task) ["posture" "head_flexion_deg"] 10))]
    (is (not= (get-in a ["loads" "cervical"]) (get-in b ["loads" "cervical"])))))

(defn- perturb [x f]
  (cond (number? x) (f x)
        (map? x) (into {} (for [[k v] x] [k (perturb v f)]))
        (vector? x) (mapv #(perturb % f) x)
        :else x))

(deftest engine-noise-is-accepted-and-reported
  ;; Every number moved by 1e-13 relative, the size measured between Node 26 and
  ;; Chrome 156: still the same answer.
  (let [cand (json-roundtrip (c/candidate (c/example-task) pin))
        noisy (update cand "results" perturb #(* % (+ 1 1e-13)))]
    (is (empty? (c/candidate-problems noisy)))
    (is (< 0 (c/max-difference (c/results (get noisy "task")) (get noisy "results")) c/tolerance))))

(deftest beyond-the-tolerance-is-refused
  (let [cand (json-roundtrip (c/candidate (c/example-task) pin))
        far (update cand "results" perturb #(* % (+ 1 1e-9)))]
    (is (seq (c/candidate-problems far)))))

(deftest a-changed-string-or-key-is-refused
  (let [cand (json-roundtrip (c/candidate (c/example-task) pin))
        k "atlanto-occipital"]
    (is (seq (c/candidate-problems (assoc-in cand ["results" "loads" "extra"] 1))))
    (is (seq (c/candidate-problems (update-in cand ["results" "strains"] pop))))
    (is (seq (c/candidate-problems (assoc-in cand ["results" "loads" k "joint"] "renamed"))))))
