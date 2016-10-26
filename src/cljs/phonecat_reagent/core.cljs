(ns phonecat-reagent.core
  (:import [goog History])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [phonecat-reagent.macros])
  (:require [reagent.core :as rg]
   [clojure.string :as str]
   [ajax.core :as ajx]
   [bidi.bidi :as b :include-macros true]
   [goog.events :as events]
   [goog.history.EventType :as EventType]
   [cljs.core.async :as a]
   [cljs-uuid-utils.core :as uuid]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Vars

(defonce debug?
  ^boolean js/goog.DEBUG)

(defn dev-setup []
  (when debug?
    (enable-console-print!)
    (println "phonecat-reagent running in dev mode.")))

;; Utility ----------------------------------------------------------------------

(defn throw-err
  "Accept a value and returns it, unless it is an Error,
  in which case it throws it."
  [v]
  (if (instance? js/Error v)
    (throw v)
    v))

;; Search logic -----------------------------------------------------------------

(defn matches-search?
  "Determines if a phone item matches a text query."
  [search data]
  (let [qp (-> search
               (or "")
               str/lower-case
               re-pattern)]
    (->> (vals data)
         (filter string?)
         (map str/lower-case)
         (some #(re-find qp %)))))

;; State ------------------------------------------------------------------------

(defonce state
  (rg/atom {:phones      []
            :search      ""
            :order-prop  :name
            :phone-by-id {}
            :navigation  {:page   :phones ;; can be any of #{:phones :phone}
                          :params {}}}))

(def navigational-state (rg/cursor state [:navigation]))

(def order-prop-state (rg/cursor state [:order-prop]))

(defn update-search
  [state new-search]
  (assoc state :search new-search))

;; Server communication ---------------------------------------------------------

(defn ajax-call
  "Accepts a cljs-ajax request map, and returns a channel which will contain the
  response, or an Error if the response is an error."
  [{:keys [method uri] :as opts}]
  (let [=resp= (a/chan)]
    (ajx/ajax-request (assoc opts
                             :handler (fn [[ok r :as data]]
                                        (if ok
                                          (a/put! =resp= r)
                                          (prn "AJAX Error" {:error r
                                                             :request opts})))))
    =resp=))

(def ajax-defaults
  "Basic options for the response format."
  {:format (ajx/json-request-format)
   :response-format (ajx/json-response-format {:keywords? true})})

(defn fetch-phones-list []
  (ajax-call (assoc ajax-defaults
                    :method :get
                    :uri "/phones/phones.json")))

(defn fetch-phone-details
  [phone-id]
  (ajax-call (assoc ajax-defaults
                    :method :get
                    :uri (str "/phones/" phone-id ".json"))))

(defn load-phones!
  "Fetches the list of phones from the server and updates the state atom with it."
  [state]
  (ajx/GET "/phones/phones.json"
    {:handler         (fn [phones]
                        (swap! state assoc :phones phones))
     :error-handler   (fn [details]
                        (.warn
                         js/console
                         (str "Failed to fetch phones: " details)))
     :response-format :json
     :keywords?       true}))

(defn load-phone-details!
  [state phone-id]
  (ajx/GET (str "/phones/" phone-id ".json")
    {:handler         (fn [phone-data]
                        (swap! state assoc-in [:phone-by-id phone-id] phone-data))
     :error-handler   (fn [details]
                        (.warn
                         js/console
                         (str "Failed to fetch phone data: " details)))
     :response-format :json
     :keywords?       true}))

(defmulti load-page-data
  "Loads data for a page and returns a fucntion which to swap! the application state."
  (fn [page params] page)
  :default :phones)

(defmethod load-page-data :phones
  [_ _]
  (go
    (let [phones (a/<! (fetch-phones-list))]
             #(assoc % :phones phones))))

(defmethod load-page-data :phone
  [_ {:keys [phone-id]}]
  (go
    (let [phone-details (a/<! (fetch-phone-details phone-id))]
       #(assoc-in % [:phone-by-id phone-id] phone-details))))

(defn watch-nav-changes! []
  (add-watch navigational-state ::watch-nav-changes
             (fn [_ _ old-state new-state]
               (when-not (= old-state new-state)
                 (let [{:keys [page params]} new-state]
                   (load-page-data page params))))))

;; Routing ----------------------------------------------------------------------

(def routes
  ["/phones" {""              :phones
              ["/" :phone-id] :phone}])

(defn url-to-nav
  [routes path]
  (let [{:keys [handler route-params]} (b/match-route routes path)]
    {:page   handler
     :params route-params}))

(defn nav-to-url
  [routes {:keys [page params]}]
  (apply b/path-for routes page (->> params
                                     seq
                                     flatten)))

(defonce h (History.))

(defn navigate-to!
  [routes nav]
  (.setToken h (nav-to-url routes nav)))

(def =path-changes=
  "A channel which will output the new value of the path when the URL changes."
  (a/chan (a/sliding-buffer 1) (comp (map (fn [event] (.-token event)))
                                     (dedupe))))


(defn hook-browser-navigation!
  "watches the path in the URL and puts change event to the =path-changes= channel."
  []
  (doto h
    (events/listen
     EventType/NAVIGATE
     (fn [event] (a/put! =path-changes= event)))
    (.setEnabled true)))

(defn listen-to-paths-changes!
  "Listen to changes in the path, resolving the new page and fetching its data,
  or falling back to last page if an error occurred"
  [routes]
  (go
    (loop [last-path "/phones"]
        (when-let [next-path (a/<! =path-changes=)]
          (let [{:keys [page params] :as nav} (url-to-nav routes next-path)
                new-last-path (cond
                                (nil? page) (do (.replaceToken h last-path) 
                                                last-path)
                                :else
                                (let [change-data (a/<! (load-page-data page params))]
                                        (swap! state change-data)
                                        (reset! navigational-state nav)
                                        next-path))]
            (recur new-last-path))))))

;; (defn hook-browser-navigation!
;;   "Listen to navigation events and updates the application state accordingly."
;;   [routes]
;;   (doto h
;;     (events/listen
;;      EventType/NAVIGATE
;;      (fn [event]
;;        (let [path (.-token event)
;;              {:keys [page params] :as nav} (url-to-nav routes path)]
;;          (if page
;;            (reset! navigational-state nav)
;;            (if (= path "")
;;              (navigate-to! routes {:page :phones})
;;              (do (.warn js/console (str "No route matches token '" path "', redirecting to /phones"))
;;                  (navigate-to! routes {:page :phones})))
;;            ))
;;        ))
;;     (.setEnabled true)))

;; View components --------------------------------------------------------------

(declare
 <phonecat-reagent>
 <phones-list-page>
 <search>
 <order-prop-select>
 <phones-list>
 <phone-item>
 <phone-page>
 <phone-detail>
 <phone-spec>
 checkmark)

(defn- find-phone-by-id
  [phones phone-id]
  (->> phones
       (filter #(= (:id %) phone-id))
       first))

(defn <phones-list-page> []
  (let [{:keys [phones search]} @state]
    [:div.row
     [:div.col-md-2
      [<search> search]
      [:br]
      "Sort by: "
      [<order-prop-select>]]
     [:div.col-md-8 [<phones-list> phones search @order-prop-state]]]))

(defn <search>
  [search]
  [:span
   "Search: "
   [:input {:type "text"
            :value search
            :on-change (fn [e]
                         (swap! state update-search (-> e
                                                        .-target
                                                        .-value)))}]])

(defn <order-prop-select> []
  [:select {:value @order-prop-state
            :on-change #(reset! order-prop-state (-> %
                                                     .-target
                                                     .-value
                                                     keyword))}
   [:option {:value :name} "Alphabetical"]
   [:option {:value :age} "Newest"]])

(defn <phones-list>
  "An unordered list of phones."
  [phones-list search order-prop]
  [:ul.phones
   (for [phone (->> phones-list
                    (filter #(matches-search? search %))
                    (sort-by order-prop))]
     ^{:key (uuid/make-random-squuid)} [<phone-item> phone])])

(defn <phone-item>
  "A phone item component."
  [{:keys [name snippet id imageUrl] :as phone}]
  (let [phone-page-href (str "#/phones/" id)]
    [:li {:class "thumbnail"}
     [:a.thumb {:href phone-page-href} [:img {:src imageUrl}]]
     [:a {:href phone-page-href} name]
     [:p snippet]]))

(defn <phone-page>
  [phone-id]
  (let [phone-cursor (rg/cursor state [:phone-by-id phone-id])
        phone        @phone-cursor]
    (cond
      phone ^{:key (uuid/make-random-squuid)} [<phone-detail> phone]
      :else [:p ])))

(defn <phone-detail> [phone]
  (let [{:keys [images]} phone
        local-state      (rg/atom {:main-image (first images)})]
    (fn [phone]
      (let [{:keys                                             [images name description availability additionalFeatures]
             {:keys [ram flash]}                               :storage
             {:keys [type talkTime standbyTime]}               :battery
             {:keys [cell wifi bluetooth infrared gps]}        :connectivity
             {:keys [os ui]}                                   :android
             {:keys [dimensions weight]}                       :sizeAndWeight
             {:keys [screenSize screenResolution touchScreen]} :display
             {:keys [cpu usb audioJack fmRadio accelerometer]} :hardware
             {:keys [primary features]}                        :camera}
            phone]
        [:div
         [:img.phone {:src (:main-image @local-state)}]
         [:h1 name]
         [:p description]

         [:ul.phone-thumbs
          (for [img images]
            ^{:key img} [:li [:img {:src img :on-click #(swap! local-state assoc :main-image img)}]])]

         [:ul.specs
          [<phone-spec> "Availability and Networks" [(cons "Availability" availability)]]
          [<phone-spec> "Battery" [["Type" type] ["Talk Time" talkTime] ["Standby time (max)" standbyTime]]]
          [<phone-spec> "Storage and Memory" [["RAM" ram] ["Internal Storage" flash]]]
          [<phone-spec> "Connectivity" [["Network Support" cell] ["WiFi" wifi] ["Bluetooth" bluetooth] ["Infrared" (checkmark infrared)] ["GPS" (checkmark gps)]]]
          [<phone-spec> "Android" [["OS Version" os] ["UI" ui]]]
          [<phone-spec> "Size and Weight" [(cons "Dimensions" dimensions) ["Weight" weight]]]
          [<phone-spec> "Display" [["Screen size" screenSize] ["Screen resolution" screenResolution] ["Touch screen" (checkmark touchScreen)]]]
          [<phone-spec> "Hardware" [["CPU" cpu] ["USB" usb] ["Audio / headphone jack" audioJack] ["FM Radio" (checkmark fmRadio)] ["Accelerometer" (checkmark accelerometer)]]]
          [<phone-spec> "Camera" [["Primary" primary] ["Features" (str/join ", " features)]]]
          [:li
           [:span "Additional Features"]
           [:dd additionalFeatures]]]]))))

(defn <phone-spec>
  [title kvs]
  [:li
   [:span title]
   [:dl (->> kvs
             (mapcat (fn [[t & ds]]
                       [^{:key (uuid/make-random-squuid)} [:dt t]
                        (for [d ds]
                          ^{:key (uuid/make-random-squuid)} [:dd d])])))]])

(defn checkmark
  [input]
  (if input \u2713 \u2718))

;; Mounting ---------------------------------------------------------------------
(defn <phonecat-reagent> []
  (let [{:keys [page params]} @navigational-state]
    [:div.container-fluid
     (case page
       :phones [<phones-list-page>]
       :phone (let [phone-id (:phone-id params)]
                [<phone-page> phone-id])
       [:div "This page does not exist."])]))

(defn reload []
  (rg/render [<phonecat-reagent>]
             (.getElementById js/document "app")))

(defn ^:export main []
  (dev-setup)
  (hook-browser-navigation!)
  (listen-to-paths-changes! routes)
  (reload))
