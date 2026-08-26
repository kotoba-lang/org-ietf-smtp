;; `kotoba/smtp/protocol_core.kotoba` against `smtp.protocol`.
;;
;; The object exists so a guest can decide SMTP success and SASL pick with
;; neither a JVM nor Node present. It cannot call this namespace, so nothing
;; but this notices the two drifting apart.
;;
;; The SASL table is checked harder than the rest: every combination of
;; advertised mechanisms and credentials, not a sample. A sampled parity
;; test agrees with a table it never visited. The trap that looks like
;; "empty AUTH" but is actually "only GSSAPI" is in that product.

(ns smtp.protocol-kotoba-parity-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [smtp.protocol :as p]))

(def ^:private kotoba-file
  (io/file (System/getProperty "user.dir") "kotoba" "smtp" "protocol_core.kotoba"))

(def ^:private cljk-file
  (io/file (System/getProperty "user.dir") "kotoba" "smtp" "protocol_core.cljk"))

(defn- source-available? []
  (let [kotoba? (.exists kotoba-file)
        cljk? (.exists cljk-file)]
    (is kotoba? (str "kotoba object not found at " kotoba-file))
    (is cljk? (str "cljk object not found at " cljk-file))
    (and kotoba? cljk?)))

(def ^:private kir
  (delay (:kir (compiler/compile-source (slurp kotoba-file) :wasm32-kotoba-v1 {}))))

(defn- call [f & args] (ir/execute @kir f (vec args)))

(deftest kotoba-objects-are-present
  (source-available?))

(deftest positive-agrees-on-every-smtp-class
  (when (source-available?)
    (doseq [code [0 199 200 250 354 399 400 421 500 550 999]]
      (is (= (p/positive? code) (call 'positive? code))
          (str "positive? " code)))))

(deftest xoauth2-continue-agrees
  (when (source-available?)
    (doseq [code [220 235 250 334 535]]
      (is (= (p/xoauth2-continue? code) (call 'xoauth2-continue? code))
          (str "xoauth2-continue? " code)))))

(deftest auth-pick-agrees-on-the-full-table
  (when (source-available?)
    (let [disagreements
          (for [n [0 1 2 3]
                xoauth2? [false true]
                plain? [false true]
                login? [false true]
                creds [0 1 2 3]
                :let [expected (p/auth-pick n xoauth2? plain? login? creds)
                      actual (call 'auth-pick n xoauth2? plain? login? creds)]
                :when (not= expected actual)]
            {:n n :xoauth2? xoauth2? :plain? plain? :login? login? :creds creds
             :expected expected :actual actual})]
      (is (empty? disagreements)
          (str "auth-pick disagreed on " (count disagreements) " rows; first: "
               (pr-str (first disagreements)))))))

(deftest auth-pick-from-matches-the-session-cases
  (when (source-available?)
    (testing "XOAUTH2 when there is a token"
      (is (= p/auth-xoauth2
             (p/auth-pick-from #{"LOGIN" "PLAIN" "XOAUTH2"} {:access-token "tok"}))))
    (testing "PLAIN over LOGIN when there is only a password"
      (is (= p/auth-plain
             (p/auth-pick-from #{"LOGIN" "PLAIN"} {:password "pw"}))))
    (testing "a server advertising no AUTH is left alone"
      (is (= p/auth-none
             (p/auth-pick-from #{} {:password "pw"}))))
    (testing "only unimplemented mechanisms is unsupported, not none"
      (is (= p/auth-unsupported
             (p/auth-pick-from #{"GSSAPI" "NTLM"} {:password "pw"})))
      (is (not= (p/auth-pick-from #{} {:password "pw"})
                (p/auth-pick-from #{"GSSAPI"} {:password "pw"}))
          "empty AUTH and GSSAPI-only must not share a code"))))

(defn- kotoba-bin []
  (or (System/getenv "KOTOBA") "kotoba"))

(deftest kotoba-cli-compiles-both-sources
  "The public compile path is `kotoba compile`, not compiler/compile-source.
  Skip rather than fail if the binary is absent — that is a host fact, not
  a disagreement between the two implementations."
  (when (source-available?)
    (if (zero? (:exit (shell/sh "which" (kotoba-bin))))
      (let [dir (io/file (System/getProperty "java.io.tmpdir")
                         (str "smtp-core-" (System/nanoTime)))]
        (.mkdirs dir)
        (doseq [[label src]
                [["kotoba" kotoba-file]
                 ["cljk" cljk-file]]
                target ["wasm" "web"]
                :let [out (io/file dir (str label "-" target
                                            (if (= target "wasm") ".wasm" ".mjs")))
                      result (shell/sh (kotoba-bin) "compile" (.getPath src)
                                       "--target" target "-o" (.getPath out))]]
          (is (zero? (:exit result))
              (str label " --target " target "\n" (:err result) (:out result)))
          (is (.isFile out) (str label " " target " emitted nothing"))))
      (println "SKIP kotoba-cli-compiles-both-sources: no kotoba on PATH"))))
