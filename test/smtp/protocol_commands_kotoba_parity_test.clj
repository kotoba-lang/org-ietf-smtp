;; `kotoba/smtp/protocol_commands.kotoba` against `smtp.protocol`.
;;
;; Command lines are product semantics: strings, not a judgement table.
;; The guest cannot require this namespace, so nothing but this notices
;; the two drifting apart. `.cljc` stays the oracle; this file does not
;; load `.kotoba` from production code.
;;
;; One-branch mutation is in `ehlo-line-refuses-a-helo-guest`: a guest
;; that emits HELO must not pass as EHLO. Reader-broken source is not
;; that test.

(ns smtp.protocol-commands-kotoba-parity-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [smtp.protocol :as p]))

(def ^:private kotoba-file
  (io/file (System/getProperty "user.dir") "kotoba" "smtp" "protocol_commands.kotoba"))

(def ^:private cljk-file
  (io/file (System/getProperty "user.dir") "kotoba" "smtp" "protocol_commands.cljk"))

(defn- source-available? []
  (let [kotoba? (.exists kotoba-file)
        cljk? (.exists cljk-file)]
    (is kotoba? (str "kotoba object not found at " kotoba-file))
    (is cljk? (str "cljk object not found at " cljk-file))
    (and kotoba? cljk?)))

(def ^:private kir
  (delay (:kir (compiler/compile-source (slurp kotoba-file) :wasm32-kotoba-v1 {}))))

(def ^:private cljk-kir
  (delay (:kir (compiler/compile-source (slurp cljk-file) :wasm32-kotoba-v1 {}))))

(defn- call [compiled f args]
  (ir/execute compiled f args))

(def ^:private cases
  [['ehlo-line ["example.com"] (p/ehlo-line "example.com")]
   ['ehlo-line ["mail.example.co.jp"] (p/ehlo-line "mail.example.co.jp")]
   ['starttls-line [] (p/starttls-line)]
   ['mail-from-line ["a@b.com"] (p/mail-from-line "a@b.com")]
   ['rcpt-to-line ["friend@example.com"] (p/rcpt-to-line "friend@example.com")]
   ['data-line [] (p/data-line)]
   ['rset-line [] (p/rset-line)]
   ['noop-line [] (p/noop-line)]
   ['quit-line [] (p/quit-line)]
   ['auth-login-line [] (p/auth-login-line)]
   ['auth-plain-line ["cGF5bG9hZA=="] (p/auth-plain-line "cGF5bG9hZA==")]
   ['auth-xoauth2-line ["dG9rZW4="] (p/auth-xoauth2-line "dG9rZW4=")]
   ['payload-line ["dXNlcg=="] (p/payload-line "dXNlcg==")]
   ['empty-line [] (p/empty-line)]])

(deftest kotoba-command-objects-are-present
  (source-available?))

(deftest command-lines-agree-with-the-cljc-oracle
  (when (source-available?)
    (doseq [[f args expected] cases]
      (is (= expected (call @kir f args))
          (str f " " (pr-str args))))))

(deftest cljk-twin-agrees-with-the-kotoba-guest
  (when (source-available?)
    (doseq [[f args] (map (juxt first second) cases)]
      (is (= (call @kir f args) (call @cljk-kir f args))
          (str "cljk drifted from kotoba on " f " " (pr-str args))))))

(deftest ehlo-line-refuses-a-helo-guest
  "A guest that emits HELO is a different protocol. Swapping the verb must
  go red. Replacing the source with unreadable text is a different failure
  and is not this test."
  (when (source-available?)
    (let [mutated (str/replace (slurp kotoba-file) "EHLO " "HELO ")
          mutated-kir (:kir (compiler/compile-source mutated :wasm32-kotoba-v1 {}))
          actual (call mutated-kir 'ehlo-line ["example.com"])]
      (is (= "HELO example.com\r\n" actual)
          "the mutation has to actually change the emitted verb")
      (is (not= (p/ehlo-line "example.com") actual)
          "HELO must not pass as EHLO"))))

(defn- kotoba-bin []
  (or (System/getenv "KOTOBA") "kotoba"))

(deftest kotoba-cli-compiles-command-sources
  "The public compile path is `kotoba compile`, not compiler/compile-source.
  Skip rather than fail if the binary is absent — that is a host fact, not
  a disagreement between the two implementations."
  (when (source-available?)
    (if (zero? (:exit (shell/sh "which" (kotoba-bin))))
      (let [dir (io/file (System/getProperty "java.io.tmpdir")
                         (str "smtp-commands-" (System/nanoTime)))]
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
      (println "SKIP kotoba-cli-compiles-command-sources: no kotoba on PATH"))))
