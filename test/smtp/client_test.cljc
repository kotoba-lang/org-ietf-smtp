(ns smtp.client-test
  (:require [clojure.test :refer [deftest is testing]]
            [smtp.client :as client]
            [smtp.fake-transport :as fake]))

(deftest connect-reads-the-greeting-and-throws-on-a-negative-one
  (let [{:keys [transport]} (fake/make ["220 smtp.example.com ESMTP ready"])]
    (is (some? (:transport (client/connect! "smtp.example.com" {:transport transport})))))
  (let [{:keys [transport]} (fake/make ["421 service not available"])]
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
         #"greeting"
         (client/connect! "smtp.example.com" {:transport transport})))))

(deftest full-session-happy-path
  (let [script ["220 smtp.example.com ESMTP ready"                 ; greeting
               "250-smtp.example.com at your service"              ; ehlo! (multi-line)
               "250-AUTH LOGIN PLAIN"
               "250 8BITMIME"
               "334 VXNlcm5hbWU6"                                  ; auth-login! username prompt
               "334 UGFzc3dvcmQ6"                                  ; password prompt
               "235 2.7.0 Authentication successful"               ; authenticated
               "250 OK"                                            ; MAIL FROM
               "250 OK"                                            ; RCPT TO
               "354 Start mail input; end with <CRLF>.<CRLF>"      ; DATA
               "250 OK: queued as 12345"                           ; DATA payload accepted
               "221 2.0.0 Bye"]                                    ; QUIT
        {:keys [transport written]} (fake/make script)
        session (client/connect! "smtp.example.com" {:transport transport})
        session (client/ehlo! session "my-client.example.com")]
    (is (= ["smtp.example.com at your service" "AUTH LOGIN PLAIN" "8BITMIME"] (:capabilities session)))
    (let [session (client/auth-login! session "you@gmail.com" "app-password")
          session (assoc session :from "you@gmail.com")
          session (client/send-mail! session {:to "friend@example.com" :subject "hi" :body "hello"})]
      (is (nil? (client/quit! session))))
    (testing "the exact wire commands sent, in order"
      (is (= ["EHLO my-client.example.com\r\n"
             "AUTH LOGIN\r\n"
             "eW91QGdtYWlsLmNvbQ==\r\n"                            ; base64("you@gmail.com")
             "YXBwLXBhc3N3b3Jk\r\n"                                ; base64("app-password")
             "MAIL FROM:<you@gmail.com>\r\n"
             "RCPT TO:<friend@example.com>\r\n"
             "DATA\r\n"
             "From: you@gmail.com\r\nTo: friend@example.com\r\nSubject: hi\r\nContent-Type: text/plain; charset=UTF-8\r\nMIME-Version: 1.0\r\n\r\nhello\r\n.\r\n"
             "QUIT\r\n"
             :closed]
             @written)))))

(deftest auth-failure-throws-with-the-servers-text
  (let [script ["220 ready" "250 OK" "535 5.7.8 Authentication failed"]
        {:keys [transport]} (fake/make script)
        session (client/connect! "smtp.example.com" {:transport transport})
        session (client/ehlo! session "client")]
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
         #"AUTH LOGIN failed"
         (client/auth-login! session "u" "wrong")))))
