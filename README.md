# org-ietf-smtp

A genuine SMTP (RFC 5321) client -- no `curl` shell-out, no
`jakarta.mail`. Zero-dep `.cljc`, an injectable transport for testing, real
`SSLSocket` I/O (implicit TLS, port 465) by default.

**Name provenance**: follows this org's `org-<standards-body>-<spec>`
naming convention (see `org-ietf-turn`, `org-ietf-ical`, `org-ietf-imap`,
`org-w3-aria`) -- SMTP is an IETF specification (RFC 5321), hence
`org-ietf-smtp`.

## Why this exists

Sibling to `kotoba-lang/org-ietf-imap`: `gftdcojp/local-manimani`'s email
channel (ADR-0022) sent replies by shelling out to `curl --url smtps://...
--upload-file -`, deliberately dependency-free. This library replaces that
shell-out with a real, portable, independently-tested SMTP client, usable
by any project that needs to send mail -- not just local-manimani, and not
Gmail-specific (a Gmail-via-REST-API integration should draft/send through
`kotoba-lang/com-gmail` instead; this client speaks plain RFC 5321 to any
SMTP submission server, Gmail's included when using an app password).

## Design

```text
smtp.transport -- Transport protocol (write!/read-line!/close!) + real SSLSocket impl (JVM-only)
smtp.protocol  -- pure: response-line parsing (multi-line-aware), command building, dot-stuffing,
                  a minimal RFC 2822 message builder, base64 (for AUTH LOGIN)
smtp.client    -- the session driver: connect!/ehlo!/auth-login!/send-mail!/quit!
```

`smtp.protocol` has zero I/O -- parsing/building is pure and tested without
a socket. `smtp.client` drives the read-until-final-line loop (SMTP
multi-line responses use `250-` for continuation, `250 ` for the last
line) over an injected `Transport` (`test/smtp/fake_transport.cljc`, a
scripted in-memory `Transport`).

## RFC 5321 coverage

| area | commands |
|---|---|
| session | connect (implicit TLS, 465), EHLO with extension parsing, STARTTLS (RFC 3207, injected upgrade), RSET, NOOP, QUIT |
| auth | AUTH LOGIN, AUTH PLAIN (RFC 4616), AUTH XOAUTH2, and `authenticate!` which picks the strongest mechanism offered |
| sending | MAIL FROM, **one or more RCPT TO in one transaction**, DATA with RFC 5321 §4.5.2 dot-stuffing |
| responses | multi-line responses, ESMTP extension map, `AUTH` mechanism set, `SIZE` limit, enhanced status codes (RFC 3463) |

**Every recipient goes in one transaction.** `send-mail!` used to take a
single `:to`, so a caller with three recipients either dropped two or opened
three transactions — and three transactions is three separate messages, each
carrying only its own address in the header, so nobody can see who else
received it and a reply-all reaches one person. RFC 5321 §3.3 has one MAIL
FROM and *one or more* RCPT TO.

A recipient the server refuses does not abort the send (§3.3 allows partial
acceptance); `send-mail!` returns `:accepted` and `:rejected`, the latter with
the RFC 3463 status, so a caller can tell `5.1.1` (no such mailbox) from
`5.7.1` (refused on policy). All recipients refused does throw.

**`:bcc` is a recipient and never a header.** A blind carbon copy is blind
because the address appears in RCPT TO and not in the message.

**Not implemented**: PIPELINING, CHUNKING/BDAT, DSN, SMTPUTF8, AUTH beyond
LOGIN/PLAIN/XOAUTH2, and message *construction* beyond `mime-message`'s
minimal plain-text form — pass `:raw` for anything with MIME parts, transfer
encodings or an RFC 2047 subject. `kotoba-lang/org-ietf-mime` is the library
for that shape.

## Usage

```clojure
(require '[smtp.client :as client])

(-> (client/connect! "smtp.gmail.com")
    (client/ehlo! "my-host.example.com")
    (client/auth-login! "you@gmail.com" "app-password")
    (assoc :from "you@gmail.com")
    (client/send-mail! {:to ["friend@example.com" "other@example.com"]
                        :cc "manager@example.com"
                        :bcc "archive@example.com"   ; delivered, never in a header
                        :subject "hi" :body "hello"
                        :in-reply-to "<original-message-id@mail.gmail.com>"})
    client/quit!)
;; send-mail! returns the session with :accepted and :rejected

;; An OAuth grant, over submission on 587:
(-> (client/connect! "smtp.office365.com" {:port 587 :tls? false})
    (client/ehlo! "my-host.example.com")
    (client/starttls! "my-host.example.com" upgrade-fn)  ; re-EHLOs, per RFC 3207 §4.2
    (client/authenticate! {:user "you@example.com" :access-token token}))
```

## Tests

```sh
clojure -M:test
```

No live server or network access required -- every `smtp.client` test
injects `smtp.fake-transport`, a scripted in-memory `Transport`.
