(ns frontend.components.content
  (:require [rum.core :as rum]
            [frontend.format :as format]
            [frontend.format.protocol :as protocol]
            [frontend.handler :as handler]
            [frontend.handler.editor :as editor-handler]
            [frontend.handler.export :as export-handler]
            [frontend.util :as util :refer-macros [profile]]
            [frontend.state :as state]
            [frontend.mixins :as mixins]
            [frontend.ui :as ui]
            [frontend.config :as config]
            [goog.dom :as gdom]
            [goog.object :as gobj]
            [dommy.core :as d]
            [clojure.string :as string]
            [frontend.components.editor :as editor]))

(defn- set-format-js-loading!
  [format value]
  (when format
    (swap! state/state assoc-in [:format/loading format] value)))

(defn- lazy-load
  [format]
  (let [format (format/normalize format)]
    (when-let [record (format/get-format-record format)]
      (when-not (protocol/loaded? record)
        (set-format-js-loading! format true)
        (protocol/lazyLoad record
                           (fn [result]
                             (set-format-js-loading! format false)))))))

(defn lazy-load-js
  [state]
  (when-let [format (:format (last (:rum/args state)))]
    (let [loader? (contains? config/html-render-formats format)]
      (when loader?
        (when-not (format/loaded? format)
          (lazy-load format))))))

(rum/defc custom-context-menu-content
  []
  [:div#custom-context-menu.w-48.rounded-md.shadow-lg.transition.ease-out.duration-100.transform.opacity-100.scale-100.enter-done.absolute {:style {:z-index 2}}
   [:div.py-1.rounded-md.bg-base-3.shadow-xs
    (ui/menu-link
     {:key "cut"
      :on-click editor-handler/cut-selection-blocks}
     "Cut")
    (ui/menu-link
     {:key "copy"
      :on-click editor-handler/copy-selection-blocks}
     "Copy")
    (ui/menu-link
     {:key "make-todos"
      :on-click editor-handler/bulk-make-todos}
     (str "Make " (state/get-preferred-todo) "s"))]])

(rum/defc block-context-menu-content
  [block-id]
  [:div#custom-context-menu.w-48.rounded-md.shadow-lg.transition.ease-out.duration-100.transform.opacity-100.scale-100.enter-done.absolute {:style {:z-index 2}}
   [:div.py-1.rounded-md.bg-base-3.shadow-xs
    (ui/menu-link
     {:key "Copy block ref"
      :on-click (fn [_e]
                  (editor-handler/copy-block-ref! block-id))}
     "Copy block ref")
    (ui/menu-link
     {:key "Focus on block"
      :on-click (fn [_e]
                  (editor-handler/focus-on-block! block-id))}
     "Focus on block")
    (ui/menu-link
     {:key "Open in sidebar"
      :on-click (fn [_e]
                  (editor-handler/open-block-in-sidebar! block-id))}
     "Open in sidebar")
    (ui/menu-link
     {:key "Cut"
      :on-click (fn [_e]
                  (editor-handler/cut-block! block-id))}
     "Cut")
    (ui/menu-link
     {:key "Copy"
      :on-click (fn [_e]
                  (export-handler/copy-block! block-id))}
     "Copy")
    (ui/menu-link
     {:key "Copy as JSON"
      :on-click (fn [_e]
                  (export-handler/copy-block-as-json! block-id))}
     "Copy as JSON")]])

;; TODO: content could be changed
;; Also, keyboard bindings should only be activated after
;; blocks were already selected.
(defn- cut-blocks-and-clear-selections!
  [_]
  (editor-handler/cut-selection-blocks)
  (editor-handler/clear-selection! nil))

(rum/defc hidden-selection < rum/reactive
  (mixins/keyboard-mixin (util/->system-modifier "ctrl+c")
                         (fn [_]
                           (editor-handler/copy-selection-blocks)
                           (editor-handler/clear-selection! nil)))
  (mixins/keyboard-mixin (util/->system-modifier "ctrl+x") cut-blocks-and-clear-selections!)
  (mixins/keyboard-mixin "backspace" cut-blocks-and-clear-selections!)
  (mixins/keyboard-mixin "delete" cut-blocks-and-clear-selections!)
  []
  [:div#selection.hidden])

(rum/defc hiccup-content < rum/static
  (mixins/event-mixin
   (fn [state]
     (mixins/listen state js/window "mouseup"
                    (fn [e]
                      (when-not (state/in-selection-mode?)
                        (when-let [blocks (seq (util/get-selected-nodes "ls-block"))]
                          (let [blocks (remove nil? blocks)
                                blocks (remove #(d/has-class? % "dummy") blocks)]
                            (when (seq blocks)
                              (doseq [block blocks]
                                (d/add-class! block "selected noselect"))
                              ;; TODO: We delay this so the following "click" event won't clear the selections.
                              ;; Needs more thinking.
                              (js/setTimeout #(state/set-selection-blocks! blocks)
                                             200)))))))

     (mixins/listen state js/window "contextmenu"
                    (fn [e]
                      (let [target (gobj/get e "target")
                            block-id (d/attr target "blockid")]
                        (cond
                          (and block-id (util/uuid-string? block-id))
                          (do
                            (util/stop e)
                            (let [client-x (gobj/get e "clientX")
                                  client-y (gobj/get e "clientY")]
                              (let [main (d/by-id "main-content")]
                                ;; disable scroll
                                (d/remove-class! main "overflow-y-auto")
                                (d/add-class! main "overflow-hidden"))

                              (state/show-custom-context-menu! (block-context-menu-content (cljs.core/uuid block-id)))
                              (when-let [context-menu (d/by-id "custom-context-menu")]
                                (d/set-style! context-menu
                                              :left (str client-x "px")
                                              :top (str client-y "px")))))

                          (state/in-selection-mode?)
                          (do
                            (util/stop e)
                            (let [client-x (gobj/get e "clientX")
                                  client-y (gobj/get e "clientY")]
                              (let [main (d/by-id "main-content")]
                                ;; disable scroll
                                (d/remove-class! main "overflow-y-auto")
                                (d/add-class! main "overflow-hidden"))

                              (state/show-custom-context-menu! (custom-context-menu-content))
                              (when-let [context-menu (d/by-id "custom-context-menu")]
                                (d/set-style! context-menu
                                              :left (str client-x "px")
                                              :top (str client-y "px")))))

                          :else
                          nil))))))
  [id {:keys [hiccup] :as option}]
  [:div {:id id}
   (if hiccup
     hiccup
     [:div.text-gray-500.cursor "Click to edit"])])

(rum/defc non-hiccup-content < rum/reactive
  [id content on-click on-hide config format]
  (let [edit? (state/sub [:editor/editing? id])
        loading (state/sub :format/loading)]
    (if edit?
      (editor/box {:on-hide on-hide
                   :format format} id)
      (let [format (format/normalize format)
            loading? (get loading format)
            markup? (contains? config/html-render-formats format)
            on-click (fn [e]
                       (when-not (util/link? (gobj/get e "target"))
                         (util/stop e)
                         (editor-handler/reset-cursor-range! (gdom/getElement (str id)))
                         (state/set-edit-content! id content)
                         (state/set-edit-input-id! id)
                         (when on-click
                           (on-click e))))]
        (cond
          (and markup? loading?)
          [:div "loading ..."]

          markup?
          (let [html (format/to-html content format config)]
            (if (string/blank? html)
              [:div.cursor.content
               {:id id
                :on-click on-click}
               [:div.text-gray-500.cursor "Click to edit"]]
              [:div.cursor.content
               {:id id
                :on-click on-click
                :dangerouslySetInnerHTML {:__html html}}]))

          :else                       ; other text formats
          [:pre.cursor.content
           {:id id
            :on-click on-click}
           (if (string/blank? content)
             [:div.text-gray-500.cursor "Click to edit"]
             content)])))))

(defn- set-fixed-width!
  []
  (when (> (gobj/get js/window "innerWidth") 1024)
    (let [blocks (d/by-class "ls-block")]
      (doseq [block blocks]
        (if (and
             (not (d/sel1 block "img"))
             (not (d/sel1 block "iframe")))
          (d/add-class! block "fixed-width")
          (d/remove-class! block "fixed-width"))))))

(defn- set-draw-iframe-style!
  []
  (let [width (gobj/get js/window "innerWidth")]
    (when (>= width 1024)
      (let [draws (d/by-class "draw-iframe")
            width (- width 200)]
        (doseq [draw draws]
          (d/set-style! draw :width (str width "px"))
          (let [height (max 700 (/ width 2))]
            (d/set-style! draw :height (str height "px")))
          (d/set-style! draw :margin-left (str (- (/ (- width 570) 2)) "px")))))))

(rum/defcs content < rum/reactive
  {:will-mount (fn [state]
                 (lazy-load-js state)
                 state)
   :did-mount (fn [state]
                (set-fixed-width!)
                (set-draw-iframe-style!)
                state)
   :did-update (fn [state]
                 (set-fixed-width!)
                 (set-draw-iframe-style!)
                 (lazy-load-js state)
                 state)}
  [state id {:keys [format
                    config
                    hiccup
                    content
                    on-click
                    on-hide]
             :as option}]
  (let [in-selection-mode? (state/sub :selection/mode)]
    (if hiccup
      [:div
       (hiccup-content id option)
       (when in-selection-mode?
         (hidden-selection))]
      (let [format (format/normalize format)]
        (non-hiccup-content id content on-click on-hide config format)))))
