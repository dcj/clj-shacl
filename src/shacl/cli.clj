(ns shacl.cli
  "Validate RDF files against SHACL shapes from the command line.

      clojure -M:cli --shapes SHAPES.ttl [--ontology ONTOLOGY.ttl]
                     [--context URL=FILE ...] [--format text|edn|json]
                     DATA...

  A DATA file ending in `.ttl` is Turtle and any other is JSON-LD. The exit
  status is 0 when the data conforms, 1 when it does not, and 2 on a usage or
  input error."
  (:require [clojure.data.json :as json]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [shacl.core :as shacl])
  (:import (org.apache.jena.graph GraphMemFactory GraphUtil)))

(def ^:private usage
  (str/join \newline
            ["Usage: clojure -M:cli --shapes SHAPES.ttl [--ontology ONTOLOGY.ttl]"
             "                      [--context URL=FILE ...] [--format text|edn|json]"
             "                      DATA..."
             ""
             "  --shapes FILE      SHACL shapes, Turtle (required)"
             "  --ontology FILE    ontology, Turtle, mixed into the data as pyshacl does"
             "  --context URL=FILE serve FILE for the JSON-LD context URL (repeatable)"
             "  --format FORMAT    text (default), edn or json"
             "  -h, --help         print this message"
             ""
             "DATA files ending in .ttl are Turtle; others are JSON-LD."
             "Exit status: 0 conforms, 1 does not conform, 2 usage or input error."]))

(def ^:private formats #{"text" "edn" "json"})

(defn- usage-error [msg] (ex-info msg {::usage true}))

(defn- parse-context
  [s]
  (let [i (str/last-index-of s "=")]
    (when (or (nil? i) (zero? i) (= i (dec (count s))))
      (throw (usage-error (str "--context wants URL=FILE, got " (pr-str s)))))
    [(subs s 0 i) (subs s (inc i))]))

(defn parse-args
  "The options in `args`, as a map, or throw ex-info on a usage error."
  [args]
  (loop [[arg & more :as args] args
         opts {:format "text" :contexts {} :data []}]
    (let [value (fn []
                  (when (or (empty? more) (str/starts-with? (first more) "--"))
                    (throw (usage-error (str arg " needs a value"))))
                  (first more))]
      (cond
        (empty? args)
        (do (when-not (:help opts)
              (when-not (:shapes opts)
                (throw (usage-error "--shapes is required")))
              (when (empty? (:data opts))
                (throw (usage-error "no DATA files"))))
            opts)

        (#{"-h" "--help"} arg) (recur more (assoc opts :help true))
        (= "--shapes" arg) (recur (next more) (assoc opts :shapes (value)))
        (= "--ontology" arg) (recur (next more) (assoc opts :ontology (value)))
        (= "--context" arg) (recur (next more)
                                   (update opts :contexts conj (parse-context (value))))
        (= "--format" arg) (let [f (value)]
                             (when-not (formats f)
                               (throw (usage-error (str "--format must be text, edn or json, got "
                                                        (pr-str f)))))
                             (recur (next more) (assoc opts :format f)))
        (str/starts-with? arg "-") (throw (usage-error (str "unknown option " arg)))
        :else (recur more (update opts :data conj arg))))))

(defn- read-data
  [{:keys [data contexts ontology]}]
  (let [{turtle true jsonld false} (group-by #(str/ends-with? % ".ttl") data)
        graph (GraphMemFactory/createDefaultGraph)]
    (when (seq jsonld)
      (GraphUtil/addInto graph (shacl/read-jsonld jsonld {:contexts contexts})))
    (doseq [f turtle]
      (GraphUtil/addInto graph (shacl/read-turtle f)))
    (when ontology
      (GraphUtil/addInto graph (shacl/inoculate (shacl/read-turtle ontology))))
    graph))

(defn- report-data
  [report]
  (update report :results (partial mapv #(dissoc % :focus-node))))

(defn- print-text
  [{:keys [conforms? results]}]
  (doseq [{:keys [severity focus path component value message]} results]
    (println (str/join \tab
                       [(name severity)
                        focus
                        (or path "-")
                        component
                        (or value "-")
                        (or message "-")])))
  (println (if conforms?
             "conforms"
             (str "does not conform: " (count results) " result(s)"))))

(defn- print-json
  [{:keys [conforms? results]}]
  (json/write {"conforms" conforms?
               "results" (mapv (fn [r]
                                 {"focus" (:focus r)
                                  "path" (:path r)
                                  "component" (:component r)
                                  "severity" (name (:severity r))
                                  "value" (:value r)
                                  "message" (:message r)
                                  "sourceShape" (:source-shape r)})
                               results)}
              *out*
              :escape-slash false
              :indent true)
  (println))

(defn run
  "Run the command line `args`, print to `*out*` and `*err*`, and return the
  exit status."
  [args]
  (try
    (let [opts (parse-args args)]
      (if (:help opts)
        (do (println usage) 0)
        (let [report (report-data (shacl/validate (read-data opts)
                                                  (shacl/read-turtle (:shapes opts))))]
          (case (:format opts)
            "text" (print-text report)
            "edn" (pprint/pprint report)
            "json" (print-json report))
          (if (:conforms? report) 0 1))))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "error: " (or (ex-message e) e)))
        (when (::usage (ex-data e))
          (println)
          (println usage)))
      2)))

(defn -main
  [& args]
  (let [status (run args)]
    (flush)
    (shutdown-agents)
    (System/exit status)))
