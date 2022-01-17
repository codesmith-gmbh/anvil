(ns ch.codesmith.anvil.pom
  (:require [clojure.data.xml :as xml]
            [clojure.tools.build.api :as b]
            [babashka.fs :as fs]))

(def pom-xmlns "http://maven.apache.org/POM/4.0.0")
(xml/alias-uri 'pom pom-xmlns)

(defmulti scm-project-url :type)

(defmethod scm-project-url :github
  [{:keys [organization project]}]
  (str "https://github.com/" organization "/" project))

(defmulti scm-connection :type)

(defmethod scm-connection :github
  [{:keys [organization project]}]
  (str "scm:git:git://github.com/" organization "/" project ".git"))

(defmulti scm-developer-connection :type)

(defmethod scm-developer-connection :github
  [{:keys [organization project]}]
  (str "scm:git:ssh://git@github.com/" organization "/" project ".git"))

(defmulti license-element identity)

(defmethod license-element :epl [_]
  [::pom/license
   [::pom/name "Eclipse Public License 1.0"]
   [::pom/url "https://www.eclipse.org/legal/epl-v10.html"]])

(defmethod license-element :mpl [_]
  [::pom/license
   [::pom/name "MPL-2.0"]
   [::pom/url "https://www.mozilla.org/media/MPL/2.0/index.txt"]])

(defmethod license-element :mit [_]
  [::pom/license
   [::pom/name "The MIT License (MIT)"]
   [::pom/url "https://opensource.org/licenses/MIT"]])

(defn pull-down [m old-key new-key]
  (when-let [val (get m old-key)]
    [new-key val]))

(defn description-xml-fragments [{:keys [description inception-year organization
                                         url scm authors license]}]
  (:content
    (xml/sexp-as-element
      (into
        [:tag]
        (keep identity)
        (let [url (or url (scm-project-url scm))]
          [(and description [::pom/description description])
           (and url [::pom/url url])
           (and inception-year [::pom/inceptionYear inception-year])
           (and organization (into [::pom/organization]
                                   (keep identity)
                                   [(pull-down organization :name ::pom/name)
                                    (pull-down organization :url ::pom/url)]))
           (and authors (into [::pom/developers]
                              (map (fn [{:keys [name email]}]
                                     (into [::pom/developer]
                                           (keep (fn [[_ value :as entry]]
                                                   (and value entry)))
                                           [[::pom/name name]
                                            [::pom/email email]])))
                              authors))
           (and license [::pom/licenses (license-element license)])
           (and scm [::pom/scm
                     [::pom/url (scm-project-url scm)]
                     [::pom/connection (scm-connection scm)]
                     [::pom/developerConnection (scm-developer-connection scm)]])])))))

(defn add-description-data [pom description-data]
  (update
    pom
    :content
    (fn [content]
      (concat content
              (description-xml-fragments description-data)))))

(defn pom-file [{:keys [class-dir lib]}]
  (fs/path class-dir
           "META-INF"
           "maven"
           (namespace lib)
           (name lib)
           "pom.xml"))

(defn write-pom [{:keys [class-dir lib version basis description-data] :as param}]
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     basis})
  (when description-data
    (let [pom-file (pom-file param)
          pom      (xml/parse-str (slurp pom-file) :skip-whitespace true)
          pom      (add-description-data pom description-data)]
      (spit pom-file (xml/indent-str pom)))))