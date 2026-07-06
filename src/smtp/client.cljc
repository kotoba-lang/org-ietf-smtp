(ns smtp.client
  "SMTP (RFC 5321) session driver: connect!/ehlo!/auth-login!/send-mail!/
  quit! over an `smtp.transport/Transport`. `smtp.protocol` supplies the
  pure command/response functions; this namespace reads (possibly
  multi-line) responses until the final line and checks status codes."
  (:require [clojure.string :as str]
            [smtp.protocol :as p]
            [smtp.transport :as t]))

(defn- read-response!
  "Read lines until the final (non-hyphen) line of a possibly multi-line
  SMTP response; returns {:code int :text \"line1\\nline2...\"}."
  [transport]
  (loop [texts []]
    (let [line (t/read-line! transport)]
      (when (nil? line) (throw (ex-info "SMTP connection closed mid-response" {:texts texts})))
      (let [{:keys [code final? text] :as parsed} (p/response-line line)]
        (when-not parsed (throw (ex-info "unparseable SMTP response line" {:line line})))
        (if final?
          {:code code :text (str/join "\n" (conj texts text))}
          (recur (conj texts text)))))))

#?(:clj
(defn connect!
  "Open the transport (real TLS unless `:transport` is given, e.g. a test
  fake) and read the server greeting. Throws if the greeting isn't
  positive (2xx/3xx). Returns a session map."
  ([host] (connect! host {}))
  ([host opts]
   (let [transport (or (:transport opts) (t/tls-connect (assoc opts :host host)))
         session {:transport transport}
         resp (read-response! transport)]
     (when-not (p/positive? (:code resp))
       (t/close! transport)
       (throw (ex-info "SMTP greeting was not positive" resp)))
     session))))

(defn- send-line!
  "Write `line` (already CRLF-terminated) and read its response."
  [{:keys [transport]} line]
  (t/write! transport line)
  (read-response! transport))

(defn- assert-positive! [{:keys [code] :as resp} verb]
  (when-not (p/positive? code)
    (throw (ex-info (str "SMTP " verb " failed: " (:text resp)) (assoc resp :verb verb))))
  resp)

(defn ehlo!
  "EHLO `domain`. Returns `session` with `:capabilities` (the server's
  multi-line EHLO response, one entry per line) attached."
  [session domain]
  (let [resp (assert-positive! (send-line! session (p/command "EHLO" domain)) "EHLO")]
    (assoc session :capabilities (str/split-lines (:text resp)))))

(defn auth-login!
  "AUTH LOGIN with a plaintext user/pass (an app password for Gmail-style
  accounts, per this library's TLS-only transport). Returns `session`."
  [session user pass]
  (assert-positive! (send-line! session (p/command "AUTH" "LOGIN")) "AUTH LOGIN")
  (assert-positive! (send-line! session (str (p/base64 user) "\r\n")) "AUTH LOGIN (username)")
  (assert-positive! (send-line! session (str (p/base64 pass) "\r\n")) "AUTH LOGIN (password)")
  session)

(defn send-mail!
  "Send one message: MAIL FROM / RCPT TO / DATA / dot-stuffed payload.
  `msg`: {:to :subject :body :in-reply-to}. `from` comes from `session`'s
  `:from` (set by the caller, e.g. (assoc session :from \"you@gmail.com\"))
  since it's the same address across every send on one connection."
  [{:keys [from] :as session} {:keys [to] :as msg}]
  (assert-positive! (send-line! session (p/command "MAIL" (str "FROM:<" from ">"))) "MAIL FROM")
  (assert-positive! (send-line! session (p/command "RCPT" (str "TO:<" to ">"))) "RCPT TO")
  (assert-positive! (send-line! session (p/command "DATA")) "DATA")
  (assert-positive! (send-line! session (p/dot-stuff (p/mime-message (assoc msg :from from)))) "DATA payload")
  session)

(defn quit!
  "QUIT and close the transport. Swallows a failed QUIT response (the
  transport is closed either way) but not a transport-level exception
  raised before that point."
  [{:keys [transport] :as session}]
  (try (send-line! session (p/command "QUIT")) (catch #?(:clj Exception :cljs :default) _ nil))
  (t/close! transport)
  nil)
