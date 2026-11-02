(ns bitch-tracker.lens.webgl-view
  "WebGL Graph View wrapper for KnoxxLens Connections Mode.
   Instantiates WebGLGraphView from @octave-commons/webgl-graph-view,
   handles canvas resizing, lifecycle, node selection, and graph updates."
  (:require ["@octave-commons/webgl-graph-view" :as webgl]))

(defn create-view
  "Mounts a WebGLGraphView instance on canvas with callbacks."
  [canvas {:keys [on-node-click]}]
  (try
    (let [opts #js {:animate true
                    :interaction true
                    :pulseAmplitude 0.4
                    :pulseSpeed (/ 1 500)
                    :onNodeClick (fn [node]
                                   (when on-node-click
                                     (on-node-click (js->clj node :keywordize-keys true))))}
          view (webgl/WebGLGraphView. canvas opts)]
      {:ok true :view view})
    (catch :default err
      (js/console.error "[webgl-view] Failed to initialize WebGLGraphView:" (.-message err))
      {:ok false :error (.-message err)})))

(defn set-graph!
  "Updates the displayed graph data on the WebGL view."
  [view-wrapper nodes edges]
  (when-let [^js view (:view view-wrapper)]
    (try
      (.setGraph view #js {:nodes (clj->js (or nodes []))
                           :edges (clj->js (or edges []))})
      (.fitToGraph view 24)
      (catch :default err
        (js/console.warn "[webgl-view] Failed to set graph:" (.-message err))))))

(defn destroy!
  "Destroys the WebGL context and cleans up event listeners."
  [view-wrapper]
  (when-let [^js view (:view view-wrapper)]
    (try
      (.destroy view)
      (catch :default _ nil))))
