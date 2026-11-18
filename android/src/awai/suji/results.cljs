(ns awai.suji.results
  "The app's \"bot results\" page: open a candidate.json written by the
  suji-posture bot, solve its task on this device and show it only when every
  number is equal. The file is read locally; nothing is sent or stored."
  (:require [awai.suji.candidate :as c]
            [clojure.string :as str]
            [jp-go-dds.core :as dds]
            [reagent.core :as r]
            [reagent.dom.client :as rdomc]
            [suji.methods.math :as math]))

(goog-define SUJI_COMMIT "unknown")

(defonce state (r/atom {:phase :empty}))

(defn- fmt [v digits]
  (if (number? v) (math/fmt-fixed v digits) (str v)))

(defn- verdict [text]
  (let [parsed (try {:ok (js->clj (js/JSON.parse text))} (catch :default e {:error (.-message e)}))]
    (if-let [e (:error parsed)]
      {:phase :refused :problems [(str "JSON として読めません: " e)]}
      (let [cand (:ok parsed)
            pin (get-in cand ["suji" "commit"])
            problems (vec (concat
                           (when (and (map? cand) (not= pin SUJI_COMMIT))
                             [(str "この端末の suji（" (subs SUJI_COMMIT 0 (min 7 (count SUJI_COMMIT)))
                                   "）と違う版で計算されています: " pin)])
                           (c/candidate-problems cand)))]
        (if (seq problems)
          {:phase :refused :problems problems
           :difference (when (and (map? cand) (map? (get cand "results")) (empty? (c/task-problems (get cand "task"))))
                         (c/first-difference (c/results (get cand "task")) (get cand "results")))}
          {:phase :accepted :candidate cand
           :max-difference (c/max-difference (c/results (get cand "task")) (get cand "results"))})))))

(defn- open-file! [file]
  (cond
    (nil? file) nil
    (> (.-size file) c/candidate-max-chars)
    (reset! state {:phase :refused :problems ["ファイルが大きすぎます"]})
    :else
    (do (reset! state {:phase :checking})
        (-> (.text file)
            (.then (fn [text] (reset! state (assoc (verdict text) :file (.-name file)))))
            (.catch (fn [e] (reset! state {:phase :refused :problems [(str "読み込めません: " (.-message e))]})))))))

(defn- task-table [task]
  (dds/table
   {:headers ["項目" "値"]
    :row-header? true
    :rows (vec (for [{:keys [json label unit]} c/fields]
                 [label (str (fmt (get-in task json) 2) " " unit)]))}))

(defn- accepted-view [cand file max-diff]
  (let [res (get cand "results")]
    [:div {:class "dds-ext-stack"}
     (dds/notification-banner {:type :success :heading "照合できました"}
                              [:p (str file " の数値は、この端末で同じ姿勢を計算した結果と、すべて許容範囲内で一致しました（"
                                       "最大の相対差 " (.toExponential max-diff 1) "、許容 " (.toExponential c/tolerance 0) "）。")])
     (dds/card
      (dds/heading 3 "頸椎にかかる圧縮荷重")
      (into [:div {:class "dds-ext-row"}]
            (for [{:keys [path label unit digits]} c/headline-fields]
              [:div {:class "suji-readout-item"}
               [:span {:class "suji-figure"} (fmt (get-in res path) digits)]
               [:span {:class "suji-unit"} (str unit " " label)]])))
     (dds/card
      (dds/heading 3 "計算した姿勢")
      (when-let [note (get-in cand ["task" "note"])] [:p {:class "suji-note"} note])
      (task-table (get cand "task")))
     [:p {:class "suji-note"}
      "力学模型が出した負荷であって、医学的な所見ではありません。この結果は確認前の候補で、どこにも送られず、保存もされません。"]]))

(defn- refused-view [{:keys [problems difference file]}]
  (dds/notification-banner
   {:type :error :heading "この結果は受け付けません"}
   (into [:ul]
         (concat
          (for [p problems] [:li p])
          (when (seq difference) [[:li (str "最初に違う値: " (str/join "." difference))]])
          (when file [[:li (str "ファイル: " file)]])))))

(defn page []
  (let [{:keys [phase] :as s} @state]
    [:div {:class "suji-shell"}
     [:main
      [:div {:class "dds-ext-container"}
       [:div {:class "dds-ext-stack"}
        [:header {:class "dds-ext-stack"}
         (dds/heading 1 "bot の結果")
         (dds/link "姿勢シミュレーションに戻る" {:href "index.html"})]
        [:p {:class "suji-note"}
         "suji-posture bot が書いた candidate.json を開くと、この端末で同じ姿勢を計算し直して、すべての数値が許容範囲内で一致したときだけ表示します。"]
        [:label {:for "candidate-file" :class "dads-label"} "candidate.json を選ぶ"]
        [:input {:id "candidate-file" :type "file" :accept "application/json,.json"
                 :on-change #(open-file! (aget (.. % -target -files) 0))}]
        (case phase
          :checking [:p {:role "status"} "計算し直しています…"]
          :accepted (accepted-view (:candidate s) (:file s) (:max-difference s))
          :refused (refused-view s)
          nil)]]]]))

(defonce root (atom nil))

(defn ^:export init! []
  (let [el (.getElementById js/document "app")]
    (reset! root (rdomc/create-root el))
    (rdomc/render @root [page])))

(defn ^:export recompute-json
  "The results this runtime computes for a task given as JSON text, as JSON
  text. For comparing runtimes; the page itself does not call it."
  [task-json]
  (js/JSON.stringify (clj->js (c/results (js->clj (js/JSON.parse task-json))))))
