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

**Scope, deliberately narrow**: implicit TLS (port 465) connect, EHLO,
AUTH LOGIN (base64 user/pass, the mechanism Gmail's SMTP submission
accepts with an app password), MAIL FROM/RCPT TO/DATA with RFC 5321
transparency (dot-stuffing), QUIT. No STARTTLS, no AUTH PLAIN/XOAUTH2, no
multi-recipient send in one call -- add these if a real use case needs
them; they don't exist speculatively here.

## Usage

```clojure
(require '[smtp.client :as client])

(-> (client/connect! "smtp.gmail.com")
    (client/ehlo! "my-host.example.com")
    (client/auth-login! "you@gmail.com" "app-password")
    (assoc :from "you@gmail.com")
    (client/send-mail! {:to "friend@example.com" :subject "hi" :body "hello"
                        :in-reply-to "<original-message-id@mail.gmail.com>"})
    client/quit!)
```

## Tests

```sh
clojure -M:test
```

No live server or network access required -- every `smtp.client` test
injects `smtp.fake-transport`, a scripted in-memory `Transport`.
