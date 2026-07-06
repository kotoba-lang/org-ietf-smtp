(ns smtp.protocol
  "Pure SMTP (RFC 5321) command construction and response parsing -- no
  I/O here. `smtp.transport` is the wire, `smtp.client` drives the
  request/response loop; this namespace only turns data into command
  strings and turns response lines back into data."
  (:require [clojure.string :as str])
  #?(:clj (:import [java.util Base64])))

(defn response-line
  "Parse one SMTP response line (\"<code>-<text>\" mid-multiline or
  \"<code> <text>\" final line, RFC 5321 4.2) into {:code int :final? bool
  :text str}, or nil if `line` isn't a well-formed response line."
  [line]
  (when-let [[_ code sep text] (re-matches #"(\d{3})([ -])(.*)" (str line))]
    {:code (parse-long code) :final? (= sep " ") :text text}))

(defn positive?
  "True for 2xx/3xx (success or positive-intermediate, e.g. DATA's 354).
  4xx/5xx are transient/permanent failures."
  [code]
  (boolean (and code (< code 400))))

(defn command
  "One CRLF-terminated command line, e.g. (command \"EHLO\" domain) ->
  \"EHLO example.com\\r\\n\", (command \"QUIT\") -> \"QUIT\\r\\n\"."
  [& parts]
  (str (str/join " " (remove nil? parts)) "\r\n"))

(defn dot-stuff
  "RFC 5321 4.5.2 transparency: double any line in `text` that begins with
  a '.', then append the DATA terminator (\"\\r\\n.\\r\\n\"). `text` may
  use \\n or \\r\\n line endings; the result always uses \\r\\n."
  [text]
  (let [lines (str/split (str/replace (str text) "\r\n" "\n") #"\n" -1)
        stuffed (map #(if (str/starts-with? % ".") (str "." %) %) lines)]
    (str (str/join "\r\n" stuffed) "\r\n.\r\n")))

(defn mime-message
  "A minimal RFC 2822 plain-text message (headers + body), CRLF-terminated
  headers, ready for `dot-stuff`."
  [{:keys [from to subject body in-reply-to]}]
  (str "From: " from "\r\n"
       "To: " to "\r\n"
       "Subject: " subject "\r\n"
       (when (not-empty in-reply-to) (str "In-Reply-To: " in-reply-to "\r\n"))
       "Content-Type: text/plain; charset=UTF-8\r\n"
       "MIME-Version: 1.0\r\n"
       "\r\n" (str body)))

#?(:clj
(defn base64 [s] (.encodeToString (Base64/getEncoder) (.getBytes (str s) "UTF-8"))))
