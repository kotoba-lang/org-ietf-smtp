(ns smtp.protocol-test
  (:require [clojure.test :refer [deftest is testing]]
            [smtp.protocol :as p]))

(deftest response-line-parses-final-vs-continuation-lines
  (is (= {:code 250 :final? true :text "OK"} (p/response-line "250 OK")))
  (is (= {:code 250 :final? false :text "PIPELINING"} (p/response-line "250-PIPELINING")))
  (is (nil? (p/response-line "not a response"))))

(deftest positive-is-true-for-2xx-and-3xx-only
  (is (true? (p/positive? 250)))
  (is (true? (p/positive? 354)))
  (is (false? (p/positive? 421)))
  (is (false? (p/positive? 550)))
  (is (false? (p/positive? nil))))

(deftest command-joins-parts-with-spaces-and-crlf-terminates
  (is (= "EHLO example.com\r\n" (p/command "EHLO" "example.com")))
  (is (= "QUIT\r\n" (p/command "QUIT")))
  (is (= "MAIL FROM:<a@b.com>\r\n" (p/command "MAIL" "FROM:<a@b.com>"))))

(deftest dot-stuff-doubles-leading-dots-and-appends-the-terminator
  (is (= "hello\r\n.\r\n" (p/dot-stuff "hello")))
  (is (= "..leading dot\r\nplain\r\n.\r\n" (p/dot-stuff ".leading dot\nplain")))
  (testing "accepts \\r\\n input too"
    (is (= "a\r\nb\r\n.\r\n" (p/dot-stuff "a\r\nb")))))

(deftest mime-message-builds-headers-and-body
  (let [msg (p/mime-message {:from "a@x.com" :to "b@x.com" :subject "hi" :body "hello"})]
    (is (re-find #"From: a@x\.com\r\n" msg))
    (is (re-find #"To: b@x\.com\r\n" msg))
    (is (re-find #"Subject: hi\r\n" msg))
    (is (re-find #"\r\n\r\nhello$" msg))))

(deftest mime-message-includes-in-reply-to-when-given
  (is (re-find #"In-Reply-To: <1@x>\r\n"
              (p/mime-message {:from "a" :to "b" :subject "s" :body "" :in-reply-to "<1@x>"}))))
