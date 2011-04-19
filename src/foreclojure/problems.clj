(ns foreclojure.problems
  (:use [foreclojure.utils]
        [clojail core testers]
	[somnium.congomongo]
        [hiccup form-helpers])
  (:require [sandbar.stateful-session :as session]
            [clojure.string :as s]))

(defn get-solved [user]
  (set
   (:solved (from-mongo
             (fetch-one :users
                        :where {:user user}
                        :only [:solved])))))

(defn get-problem [x]
  (from-mongo
   (fetch-one :problems :where {:_id x})))

(defn get-problem-list []
  (from-mongo
   (fetch :problems
          :only [:_id :title :tags :times-solved]
          :sort {:id 1})))

(defn mark-completed [id]
  (if-let [user (session/session-get :user)]
    (do
      (when (not-any? #{id} (get-solved user))
        (update! :users {:user user} {:$push {:solved id}})
        (update! :problems {:_id id} {:$inc {:times-solved 1}}))
      (flash-msg "Congratulations, you've solved the problem!" "/problems")) 
    (flash-msg "You've solved the problem! If you log in we can track your progress." "/problems")))

(defn get-tester [restricted]
  (into secure-tester (map symbol restricted)))

(def sb (sandbox*))

(defn run-code [id raw-code]
  (let [code (.trim raw-code)
	p (get-problem id)
        tests (concat (:tests p) (:secret-tests p))
        func-name (:function-name p)
        sb-tester (get-tester (:restricted p))]
    (if (empty? code)
      (do
	(session/flash-put! :code code)
	(flash-error "Empty input is not allowed"
		     (str "/problem/" id)))
      (try
	(loop [[test & more] tests]
	  (if-not test
	    (mark-completed id)
	    (let [testcase (s/replace test "__" (str code))]
	      (if (sb sb-tester (read-string testcase))
		(recur more)
		(do
		  (session/flash-put! :code code)
		  (flash-error "You failed the unit tests."
			       (str "/problem/" id)))))))
	(catch Exception e
	  (do
	    (session/flash-put! :code code)
	    (flash-error (.getMessage e) (str "/problem/" id))))))))


(def-page code-box [id]
  (let [problem (get-problem (Integer. id))]
    [:div
     [:span {:id "prob-title"} (problem :title)]
     [:hr]
     [:div {:id "prob-desc"}
      (problem :description)[:br]
      [:div {:id "testcases"}
       (for [test (:tests problem)]
         [:li {:class "testcase"} test])]
      (if-let [restricted (problem :restricted)]
        [:div {:id "restrictions"}
         [:u "Special Restrictions"] [:br]
         (map (partial vector :li) restricted)])]
     [:div
      [:b "Enter your code:" [:br]
       [:span {:class "error"} (session/flash-get :error)]]]
     (form-to [:post "/run-code"] 
              (text-area {:id "code-box"
                          :spellcheck "false"}
                         :code (session/flash-get :code))
              (hidden-field :id id)
              (submit-button {:type "image" :src "/run.png"} "Run"))]))

(def-page problem-page []
  [:div {:class "congrats"} (session/flash-get :message)]
  [:table {:class "my-table" :width "60%"}
   [:th "Title"]
   [:th "Tags"]
   [:th "Count"]
   [:th "Solved?"]
   (let [solved (get-solved (session/session-get :user))
         problems (get-problem-list)]
     (map-indexed
      (fn [x {:keys [title times-solved tags], id :_id}]
        [:tr (row-class x)
         [:td {:class "title-link"}
          [:a {:href (str "/problem/" id)}
           title]]
         [:td {:class "centered"}
          (s/join " " (map #(str "<span class='tag'>" % "</span>")
                           tags))]
         [:td {:class "centered"} (int times-solved)]
         [:td {:class "centered"}
          [:img {:src (if (contains? solved id)
                        "/checkmark.png"
                        "/empty-sq.png")}]]])
      problems))])
