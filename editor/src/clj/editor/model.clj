;; Copyright 2020-2023 The Defold Foundation
;; Copyright 2014-2020 King
;; Copyright 2009-2014 Ragnar Svensson, Christian Murray
;; Licensed under the Defold License version 1.0 (the "License"); you may not use
;; this file except in compliance with the License.
;;
;; You may obtain a copy of the License, together with FAQs at
;; https://www.defold.com/license
;;
;; Unless required by applicable law or agreed to in writing, software distributed
;; under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
;; CONDITIONS OF ANY KIND, either express or implied. See the License for the
;; specific language governing permissions and limitations under the License.

(ns editor.model
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dynamo.graph :as g]
            [editor.animation-set :as animation-set]
            [editor.build-target :as bt]
            [editor.core :as core]
            [editor.defold-project :as project]
            [editor.geom :as geom]
            [editor.gl.pass :as pass]
            [editor.gl.texture :as texture]
            [editor.graph-util :as gu]
            [editor.image :as image]
            [editor.material :as material]
            [editor.model-scene :as model-scene]
            [editor.properties :as properties]
            [editor.protobuf :as protobuf]
            [editor.resource :as resource]
            [editor.resource-node :as resource-node]
            [editor.rig :as rig]
            [editor.validation :as validation]
            [editor.workspace :as workspace]
            [internal.util :as util]
            [schema.core :as s]
            [util.coll :refer [pair]]
            [util.digest :as digest])
  (:import [com.dynamo.gamesys.proto ModelProto$Model ModelProto$ModelDesc]
           [editor.gl.shader ShaderLifecycle]
           [editor.types AABB]))

(set! *warn-on-reflection* true)

(def ^:private model-icon "icons/32/Icons_22-Model.png")

(g/defnk produce-animation-set-build-target-single [_node-id resource animations-resource animation-set]
  (let [is-single-anim (and (not (empty? animation-set))
                            (not (animation-set/is-animation-set? animations-resource)))]
    (when is-single-anim
      (rig/make-animation-set-build-target (resource/workspace resource) _node-id animation-set))))

(g/defnk produce-animation-ids [_node-id resource animations-resource animation-set-info animation-set animation-ids]
  (let [is-single-anim (or (empty? animation-set)
                           (not (animation-set/is-animation-set? animations-resource)))]
    (if is-single-anim
      (if animations-resource
        animation-ids
        [])
      (:animation-ids animation-set-info))))

(g/defnk produce-pb-msg [name mesh materials skeleton animations default-animation]
  (cond-> {:mesh (resource/resource->proj-path mesh)
           :materials (mapv
                        (fn [material]
                          (-> material
                              (update :material resource/resource->proj-path)
                              (update :textures
                                      (fn [textures]
                                        (mapv #(update % :texture resource/proj-path) textures)))))
                        materials)
           :skeleton (resource/resource->proj-path skeleton)
           :animations (resource/resource->proj-path animations)
           :default-animation default-animation}
          (not (str/blank? name))
          (assoc :name name)))

(defn- build-pb [resource dep-resources {:keys [pb] :as user-data}]
  (let [pb (reduce-kv
             (fn [acc path res]
               (assoc-in acc path (resource/resource->proj-path (get dep-resources res))))
             pb
             (:dep-resources user-data))]
    {:resource resource :content (protobuf/map->bytes ModelProto$Model pb)}))

(defn- prop-resource-error [nil-severity _node-id prop-kw prop-value prop-name]
  (or (validation/prop-error nil-severity _node-id prop-kw validation/prop-nil? prop-value prop-name)
      (validation/prop-error :fatal _node-id prop-kw validation/prop-resource-not-exists? prop-value prop-name)))

(defn- res-fields->resources [pb-msg deps-by-source fields]
  (letfn [(fill-from-key-path [acc source acc-path key-path-index key-path]
            (let [end (= key-path-index (count key-path))]
              (if end
                (let [dep (get deps-by-source source ::not-found)]
                  (if (identical? dep ::not-found)
                    acc
                    (assoc! acc acc-path dep)))
                (let [k (key-path key-path-index)
                      v (source k)
                      acc-path (conj acc-path k)]
                  (if (vector? v)
                    (reduce-kv
                      (fn [acc i item]
                        (let [acc-path (conj acc-path i)]
                          (fill-from-key-path acc item acc-path (inc key-path-index) key-path)))
                      acc
                      v)
                    (fill-from-key-path acc v acc-path (inc key-path-index) key-path))))))]
    (persistent!
      (reduce
        (fn [acc field]
          (let [key-path (if (vector? field) field [field])]
            (fill-from-key-path acc pb-msg [] 0 key-path)))
        (transient {})
        fields))))

(defn- validate-default-animation [_node-id default-animation animation-ids]
  (when (not (str/blank? default-animation))
    (validation/prop-error :fatal _node-id :default-animation validation/prop-member-of? default-animation (set animation-ids)
                           (format "Animation '%s' does not exist" default-animation))))

(g/defnk produce-build-targets [_node-id resource pb-msg dep-build-targets default-animation animation-ids animation-set-build-target animation-set-build-target-single mesh-set-build-target materials skeleton-build-target animations mesh skeleton]
  (or (some->> (into [(prop-resource-error :fatal _node-id :mesh mesh "Mesh")
                      (validation/prop-error :fatal _node-id :skeleton validation/prop-resource-not-exists? skeleton "Skeleton")
                      (validation/prop-error :fatal _node-id :animations validation/prop-resource-not-exists? animations "Animations")
                      (validate-default-animation _node-id default-animation animation-ids)
                      (validation/prop-error :fatal _node-id :materials validation/prop-empty? (:materials pb-msg) "Materials")]
                     (map (fn [{:keys [name material]}]
                            (validation/prop-error
                              :fatal _node-id
                              :materials validation/prop-resource-missing?
                              material name)))
                     materials)
               (filterv identity)
               not-empty
               g/error-aggregate)
      (let [workspace (resource/workspace resource)
            animation-set-build-target (if (nil? animation-set-build-target-single) animation-set-build-target animation-set-build-target-single)
            rig-scene-type (workspace/get-resource-type workspace "rigscene")
            rig-scene-pseudo-data (digest/string->sha1-hex (str/join (map #(-> % :resource :resource :data) [animation-set-build-target mesh-set-build-target skeleton-build-target])))
            rig-scene-resource (resource/make-memory-resource workspace rig-scene-type rig-scene-pseudo-data)
            rig-scene-dep-build-targets {:animation-set animation-set-build-target
                                         :mesh-set mesh-set-build-target
                                         :skeleton skeleton-build-target}
            rig-scene-pb-msg {:texture-set ""} ; Set in the ModelProto$Model message. Other field values taken from build targets.
            rig-scene-additional-resource-keys []
            rig-scene-build-targets (rig/make-rig-scene-build-targets _node-id rig-scene-resource rig-scene-pb-msg dep-build-targets rig-scene-additional-resource-keys rig-scene-dep-build-targets)
            pb-msg (select-keys pb-msg [:materials :default-animation])
            dep-build-targets (into rig-scene-build-targets (flatten dep-build-targets))
            deps-by-source (into {}
                                 (map (fn [build-target]
                                        (let [build-resource (:resource build-target)
                                              source-resource (:resource build-resource)]
                                          [(resource/proj-path source-resource) build-resource])))
                                 dep-build-targets)
            dep-resources (res-fields->resources pb-msg deps-by-source
                                                 [:rig-scene
                                                  [:materials :material]
                                                  [:materials :textures :texture]])]
        [(bt/with-content-hash
           {:node-id _node-id
            :resource (workspace/make-build-resource resource)
            :build-fn build-pb
            :user-data {:pb pb-msg
                        :dep-resources dep-resources}
            :deps dep-build-targets})])))

(g/defnk produce-gpu-textures [_node-id samplers texture-binding-infos :as m]
  (let [sampler-name->gpu-texture-generator (into {}
                                                  (keep (fn [{:keys [sampler gpu-texture-generator]}]
                                                          (when gpu-texture-generator
                                                            [sampler gpu-texture-generator])))
                                                  texture-binding-infos)
        explicit-textures (into {}
                                (keep-indexed
                                  (fn [unit-index {:keys [name] :as sampler}]
                                    (when-let [{tex-fn :f tex-args :args} (sampler-name->gpu-texture-generator name)]
                                      (let [request-id [_node-id unit-index]
                                            params (material/sampler->tex-params sampler)
                                            texture (tex-fn tex-args request-id params unit-index)]
                                        [name texture]))))
                                samplers)
        fallback-texture (if (pos? (count explicit-textures))
                           (val (first explicit-textures))
                           @texture/black-pixel)]
    (reduce
      (fn [acc {:keys [name]}]
        (cond-> acc (not (acc name)) (assoc name fallback-texture)))
      explicit-textures
      samplers)))

(g/defnk produce-scene [_node-id scene mesh-material-ids material-scene-infos]
  (if (not scene)
    {:aabb geom/empty-bounding-box
     :renderable {:passes [pass/selection]}}
    (let [{:keys [renderable aabb]} scene
          material-index->meshes (->> renderable :user-data :meshes (group-by :material-index))
          name->material-scene-info (into {}
                                          (map (juxt :name identity))
                                          material-scene-infos)
          material-index->material-scene-info (into {}
                                                    (keep-indexed
                                                      (fn [i name]
                                                        (when-let [info (name->material-scene-info name)]
                                                          (pair i info))))
                                                    mesh-material-ids)]
      {:aabb geom/empty-bounding-box
       :renderable {:passes [pass/selection]}
       :children
       (into (:children scene [])
             (keep (fn [[material-index meshes]]
                     (when-let [{:keys [shader vertex-space gpu-textures]}
                                ;; If we have no material associated with the index,
                                ;; we mirror the engine behavior by picking the first one:
                                ;; https://github.com/defold/defold/blob/a265a1714dc892eea285d54eae61d0846b48899d/engine/gamesys/src/gamesys/resources/res_model.cpp#L234-L238
                                (or (material-index->material-scene-info material-index)
                                    (first material-scene-infos))]
                       {:node-id _node-id
                        :aabb aabb
                        :renderable (-> renderable
                                        (dissoc :children)
                                        (assoc-in [:user-data :shader] shader)
                                        (assoc-in [:user-data :vertex-space] vertex-space)
                                        (assoc-in [:user-data :textures] gpu-textures)
                                        (assoc-in [:user-data :meshes] meshes)
                                        (update :batch-key
                                                (fn [old-key]
                                                  ;; We can only batch-render models that use
                                                  ;; :vertex-space-world. In :vertex-space-local
                                                  ;; we must supply individual transforms for
                                                  ;; each model instance in the shader uniforms.
                                                  (when (= :vertex-space-world vertex-space)
                                                    [old-key shader gpu-textures]))))})))
             material-index->meshes)})))

(g/defnk produce-bones [skeleton-bones animations-bones]
  (or animations-bones skeleton-bones))

(def TTexture
  {:sampler s/Str
   :texture (s/maybe (s/protocol resource/Resource))})

(g/deftype Materials
  [{:name s/Str
    :material (s/maybe (s/protocol resource/Resource))
    :textures [TTexture]}])

(g/defnode TextureBinding
  (property sampler g/Str (default ""))
  (property texture resource/Resource
            (value (gu/passthrough texture-resource))
            (set (fn [evaluation-context self old-value new-value]
                   (project/resource-setter evaluation-context self old-value new-value
                                            [:resource :texture-resource]
                                            [:gpu-texture-generator :gpu-texture-generator]
                                            [:build-targets :build-targets]))))
  (input texture-resource resource/Resource)
  (input gpu-texture-generator g/Any)
  (input build-targets g/Any :array)
  (output build-targets g/Any (gu/passthrough build-targets))
  (output texture-binding-info g/Any
          (g/fnk [_node-id sampler texture ^:try gpu-texture-generator :as info]
            (cond-> info (g/error-value? gpu-texture-generator) (dissoc :gpu-texture-generator)))))

(defn- detect-and-apply-renames [texture-binding-infos samplers]
  (let [texture-binding-info-name-index (util/name-index texture-binding-infos :sampler)
        sampler-name-index (util/name-index samplers :name)
        renames (util/detect-renames texture-binding-info-name-index sampler-name-index)]
    (reduce
      (fn [texture-binding-infos [texture-binding-name+order [new-name]]]
        (update texture-binding-infos
                (texture-binding-info-name-index texture-binding-name+order)
                assoc :sampler new-name))
      texture-binding-infos
      renames)))

(g/defnode MaterialBinding
  (inherits core/Scope)
  (input dep-build-targets g/Any :array)
  (input shader ShaderLifecycle)
  (input vertex-space g/Keyword)
  (input samplers g/Any)

  (property name g/Str (default ""))
  (property material resource/Resource
            (value (gu/passthrough material-resource))
            (set (fn [evaluation-context self old-value new-value]
                   (project/resource-setter evaluation-context self old-value new-value
                                            [:resource :material-resource]
                                            [:build-targets :dep-build-targets]
                                            [:samplers :samplers]
                                            [:shader :shader]
                                            [:vertex-space :vertex-space]))))
  (input material-resource resource/Resource)
  (input texture-binding-infos g/Any :array)
  (output gpu-textures g/Any :cached produce-gpu-textures)
  (output dep-build-targets g/Any (gu/passthrough dep-build-targets))
  (output material-scene-info g/Any (g/fnk [shader vertex-space gpu-textures name :as info] info))
  (output material-binding-info g/Any (g/fnk [_node-id name material ^:try samplers ^:try texture-binding-infos :as info]
                                        (cond
                                          (g/error-value? texture-binding-infos) (assoc info :texture-binding-infos [])
                                          (g/error-value? samplers) (dissoc info :samplers)
                                          :else (update info :texture-binding-infos detect-and-apply-renames samplers)))))

(defmethod material/handle-sampler-names-changed ::MaterialBinding
  [evaluation-context material-binding-node old-name-index _new-name-index sampler-renames sampler-deletions]
  (let [texture-binding-infos (g/node-value material-binding-node :texture-binding-infos evaluation-context)
        texture-binding-name-index (util/name-index texture-binding-infos :sampler)
        implied-texture-binding-info-renames (util/detect-renames texture-binding-name-index old-name-index)]
    (into []
          (mapcat
            (fn [[name+order index]]
              ;; Texture binding could be implicitly renamed if its name does
              ;; not match the material sampler name (can happen on load)
              (let [name+order (implied-texture-binding-info-renames name+order name+order)]
                (concat
                  (when-let [[new-name] (sampler-renames name+order)]
                    (g/set-property (:_node-id (texture-binding-infos index)) :sampler new-name))
                  (when (sampler-deletions name+order)
                    (g/delete-node (:_node-id (texture-binding-infos index))))))))
          texture-binding-name-index)))

(defn- create-texture-binding-tx [material-binding sampler texture]
  (g/make-nodes (g/node-id->graph-id material-binding) [texture-binding [TextureBinding
                                                                         :sampler sampler
                                                                         :texture texture]]
    (g/connect texture-binding :_node-id material-binding :nodes)
    (g/connect texture-binding :texture-binding-info material-binding :texture-binding-infos)
    (g/connect texture-binding :build-targets material-binding :dep-build-targets)))

(defn- create-material-binding-tx [model-node-id name material textures]
  (g/make-nodes (g/node-id->graph-id model-node-id) [material-binding [MaterialBinding
                                                                       :name name
                                                                       :material material]]
    (g/connect material-binding :_node-id model-node-id :nodes)
    (g/connect material-binding :dep-build-targets model-node-id :dep-build-targets)
    (g/connect material-binding :material-scene-info model-node-id :material-scene-infos)
    (g/connect material-binding :material-binding-info model-node-id :material-binding-infos)
    (for [{:keys [sampler texture]} textures]
      (create-texture-binding-tx material-binding sampler texture))))

(def ^:private fake-resource
  (reify resource/Resource
    (children [_])
    (ext [_] "")
    (resource-type [_])
    (source-type [_])
    (exists? [_] false)
    (read-only? [_] true)
    (path [_] "")
    (abs-path [_] "")
    (proj-path [_] "")
    (resource-name [_] "")
    (workspace [_])
    (resource-hash [_])
    (openable? [_] false)
    (editable? [_] false)))

(g/defnk produce-model-properties [_node-id _declared-properties material-binding-infos mesh-material-ids]
  (let [model-node-id _node-id
        mesh-material-names (if (g/error-value? mesh-material-ids) #{} (set mesh-material-ids))
        proto-material-name->material-binding-info (into {} (map (juxt :name identity)) material-binding-infos)
        proto-material-names (into #{} (map :name) material-binding-infos)
        all-material-names (set/union mesh-material-names proto-material-names)
        new-props
        (into []
              (comp
                (map-indexed
                  (fn [index material-name]
                    (let [material-prop-key (keyword (str "__material__" index))]
                      (if-let [{:keys [_node-id material name texture-binding-infos samplers]} (proto-material-name->material-binding-info material-name)]
                        ;; material exists
                        (let [sampler-name-index (util/name-index samplers :name)
                              texture-binding-name-index (util/name-index texture-binding-infos :sampler)
                              all-sampler-name+orders (set/union
                                                        (set (keys sampler-name-index))
                                                        (set (keys texture-binding-name-index)))
                              should-be-deleted (not (mesh-material-names name))
                              material-binding-node-id _node-id]
                          (into [[material-prop-key
                                  (cond-> {:node-id material-binding-node-id
                                           :label name
                                           :type resource/Resource
                                           :value (cond-> material should-be-deleted (or fake-resource))
                                           :error (or
                                                    (when should-be-deleted
                                                      (g/->error material-binding-node-id :materials :warning material
                                                                 (format "'%s' is not defined in the mesh. Clear the field to delete it."
                                                                         name)))
                                                    (prop-resource-error :fatal material-binding-node-id :materials material "Material"))
                                           :prop-kw :material
                                           :edit-type {:type resource/Resource
                                                       :ext "material"
                                                       :clear-fn (fn [_ _]
                                                                   (g/delete-node material-binding-node-id))}}
                                          should-be-deleted
                                          (assoc :original-value fake-resource))]]
                                (map-indexed
                                  (fn [binding-index sampler-name+order]
                                    (let [texture-binding-prop-key (keyword (str "__sampler__" index "__" binding-index))]
                                      ;; texture binding exists
                                      (if-let [texture-binding-index (texture-binding-name-index sampler-name+order)]
                                        (let [{:keys [sampler texture _node-id]} (texture-binding-infos texture-binding-index)
                                              texture-binding-should-be-deleted (and samplers (not (sampler-name-index sampler-name+order)))]
                                          [texture-binding-prop-key
                                           (cond-> {:node-id _node-id
                                                    :label sampler
                                                    :type resource/Resource
                                                    :value (cond-> texture texture-binding-should-be-deleted (or fake-resource))
                                                    :prop-kw :texture
                                                    :error (when texture-binding-should-be-deleted
                                                             (g/->error _node-id :texture :warning texture
                                                                        (format "'%s' is not defined in the material. Clear the field to delete it."
                                                                                sampler)))
                                                    :edit-type {:type resource/Resource
                                                                :ext (conj image/exts "cubemap")
                                                                :clear-fn (fn [_ _] (g/delete-node _node-id))}}
                                                   texture-binding-should-be-deleted
                                                   (assoc :original-value fake-resource))])
                                        ;; texture binding does not exist
                                        (let [sampler (key sampler-name+order)]
                                          [texture-binding-prop-key
                                           {:node-id material-binding-node-id
                                            :label sampler
                                            :value nil
                                            :type resource/Resource
                                            :edit-type {:type resource/Resource
                                                        :ext (conj image/exts "cubemap")
                                                        :set-fn (fn [_ _ _ new] (create-texture-binding-tx material-binding-node-id sampler new))}}])))))
                                (sort-by key all-sampler-name+orders)))
                        ;; material does not exist
                        [[material-prop-key
                          {:node-id _node-id
                           :label material-name
                           :value nil
                           :type resource/Resource
                           :error (prop-resource-error :fatal _node-id :material nil "Material")
                           :edit-type {:type resource/Resource
                                       :ext "material"
                                       :set-fn (fn [_evaluation-context _id _old new]
                                                 (create-material-binding-tx model-node-id material-name new []))}}]]))))
                cat)
              (sort all-material-names))]
    (-> _declared-properties
        (update :properties into new-props)
        (update :display-order into (map first) new-props))))

(g/defnode ModelNode
  (inherits resource-node/ResourceNode)

  (property name g/Str (dynamic visible (g/constantly false)))
  (property mesh resource/Resource
            (value (gu/passthrough mesh-resource))
            (set (fn [evaluation-context self old-value new-value]
                   (project/resource-setter evaluation-context self old-value new-value
                                            [:resource :mesh-resource]
                                            [:aabb :aabb]
                                            [:mesh-set-build-target :mesh-set-build-target]
                                            [:material-ids :mesh-material-ids]
                                            [:scene :scene])))
            (dynamic error (g/fnk [_node-id mesh]
                                  (prop-resource-error :fatal _node-id :mesh mesh "Mesh")))
            (dynamic edit-type (g/constantly {:type resource/Resource
                                              :ext model-scene/model-file-types})))
  (input material-binding-infos g/Any :array)
  (output materials Materials :cached
          (g/fnk [material-binding-infos]
            (mapv
              (fn [{:keys [name material texture-binding-infos]}]
                {:name name
                 :material material
                 :textures (into []
                                 (keep (fn [{:keys [sampler texture]}]
                                         (when texture
                                           {:sampler sampler :texture texture})))
                                 texture-binding-infos)})
              material-binding-infos)))
  (input scene g/Any)
  (input material-scene-infos g/Any :array)
  (property skeleton resource/Resource
            (value (gu/passthrough skeleton-resource))
            (set (fn [evaluation-context self old-value new-value]
                   (project/resource-setter evaluation-context self old-value new-value
                                            [:resource :skeleton-resource]
                                            [:bones :skeleton-bones]
                                            [:skeleton-build-target :skeleton-build-target])))
            (dynamic error (g/fnk [_node-id skeleton]
                                  (validation/prop-error :fatal _node-id :skeleton validation/prop-resource-not-exists? skeleton "Skeleton")))
            (dynamic edit-type (g/constantly {:type resource/Resource
                                              :ext model-scene/model-file-types})))
  (property animations resource/Resource
            (value (gu/passthrough animations-resource))
            (set (fn [evaluation-context self old-value new-value]
                   (project/resource-setter evaluation-context self old-value new-value
                                            [:resource :animations-resource]
                                            [:bones :animations-bones]
                                            [:animation-ids :animation-ids]
                                            [:animation-info :animation-infos]
                                            [:animation-set-build-target :animation-set-build-target])))
            (dynamic error (g/fnk [_node-id animations]
                                  (validation/prop-error :fatal _node-id :animations validation/prop-resource-not-exists? animations "Animations")))
            (dynamic edit-type (g/constantly {:type resource/Resource
                                              :ext model-scene/animation-file-types})))
  (property default-animation g/Str
            (dynamic error (g/fnk [_node-id default-animation animation-ids]
                                  (validate-default-animation _node-id default-animation animation-ids)))
            (dynamic edit-type (g/fnk [animation-ids]
                                      (properties/->choicebox (into [""] animation-ids)))))

  (input mesh-resource resource/Resource)
  (input mesh-set-build-target g/Any)
  (input mesh-material-ids g/Any)

  (input skeleton-resource resource/Resource)
  (input skeleton-build-target g/Any)
  (input animations-resource resource/Resource)
  (input animation-set-build-target g/Any)
  (input dep-build-targets g/Any :array)

  (input skeleton-bones g/Any)
  (input animations-bones g/Any)

  (input animation-infos g/Any :array)
  (input animation-ids g/Any)
  (input aabb AABB)

  (output bones g/Any produce-bones)

  (output animation-resources g/Any (g/fnk [animations-resource] [animations-resource]))

  (output animation-info g/Any :cached animation-set/produce-animation-info)
  (output animation-set-info g/Any :cached animation-set/produce-animation-set-info)
  (output animation-set g/Any :cached animation-set/produce-animation-set)
  (output animation-ids g/Any :cached produce-animation-ids)

  ; if we're referencing a single animation file
  (output animation-set-build-target-single g/Any :cached produce-animation-set-build-target-single)

  (output pb-msg g/Any :cached produce-pb-msg)
  (output save-value g/Any (gu/passthrough pb-msg))
  (output build-targets g/Any :cached produce-build-targets)

  (output scene g/Any :cached produce-scene)

  (output aabb AABB (gu/passthrough aabb))
  (output _properties g/Properties :cached produce-model-properties))

(defn load-model [_project self resource {:keys [name default-animation mesh skeleton animations materials]}]
  (concat
    (g/set-property self
      :name name
      :default-animation default-animation
      :mesh (workspace/resolve-resource resource mesh)
      :skeleton (workspace/resolve-resource resource skeleton)
      :animations (workspace/resolve-resource resource animations))
    (for [{:keys [name material textures]} materials
          :let [material (workspace/resolve-resource resource material)
                textures (mapv (fn [{:keys [texture] :as texture-desc}]
                                 (assoc texture-desc :texture (workspace/resolve-resource resource texture)))
                               textures)]]
      (create-material-binding-tx self name material textures))))

(defn- sanitize-model [{:keys [material textures materials] :as pb}]
  (-> pb
      (dissoc :material :textures)
      (cond-> (and (zero? (count materials))
                   (or (pos? (count material))
                       (pos? (count textures))))
              (assoc :materials [{:name "default"
                                  :material material
                                  :textures (into []
                                                  (map-indexed
                                                    (fn [i tex-name]
                                                      {:sampler (str "tex" i)
                                                       :texture tex-name}))
                                                  textures)}]))))

(defn register-resource-types [workspace]
  (resource-node/register-ddf-resource-type workspace
    :ext "model"
    :label "Model"
    :node-type ModelNode
    :ddf-type ModelProto$ModelDesc
    :load-fn load-model
    :sanitize-fn sanitize-model
    :icon model-icon
    :view-types [:scene :text]
    :tags #{:component}
    :tag-opts {:component {:transform-properties #{:position :rotation}}}))
