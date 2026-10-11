#!/usr/bin/env python3
"""Turn play/privacy-policy.md into a self-contained page (play/privacy-policy.html) for hosting.

Handles the small Markdown subset the policy uses: #/## headings, "- " bullets, **bold**,
paragraphs. Pass --email you@example.com to fill the [CONTACT_EMAIL] placeholder.
"""
import argparse
import html
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "play" / "privacy-policy.md"
DST = ROOT / "play" / "privacy-policy.html"

STYLE = """
body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;max-width:680px;
margin:40px auto;padding:0 20px;line-height:1.6;color:#191c21;background:#fafaf8}
h1{font-size:1.8em;margin-bottom:.2em}h2{font-size:1.2em;margin-top:1.8em}
@media (prefers-color-scheme:dark){body{color:#e7eaee;background:#0b0e12}a{color:#7fd1a7}}
"""


def inline(text, email):
    text = html.escape(text)
    if email:
        e = html.escape(email)
        text = text.replace("[CONTACT_EMAIL]", e)
    text = re.sub(r"[\w.+-]+@[\w-]+\.[\w.-]+", lambda m: f'<a href="mailto:{m[0]}">{m[0]}</a>', text)
    return re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", text)


def convert(md, email):
    out, para, in_list = [], [], False

    def flush():
        nonlocal para
        if para:
            out.append(f"<p>{inline(' '.join(para), email)}</p>")
            para = []

    for line in md.splitlines():
        s = line.strip()
        if s.startswith("- "):
            flush()
            if not in_list:
                out.append("<ul>")
                in_list = True
            out.append(f"<li>{inline(s[2:], email)}</li>")
            continue
        if in_list:
            out.append("</ul>")
            in_list = False
        if not s:
            flush()
        elif s.startswith("## "):
            flush()
            out.append(f"<h2>{inline(s[3:], email)}</h2>")
        elif s.startswith("# "):
            flush()
            out.append(f"<h1>{inline(s[2:], email)}</h1>")
        else:
            para.append(s)
    flush()
    if in_list:
        out.append("</ul>")
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--email", default="")
    args = ap.parse_args()
    body = convert(SRC.read_text(), args.email)
    DST.write_text(
        "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        f"<title>aShorts privacy policy</title><style>{STYLE}</style></head>\n"
        f"<body>\n{body}\n</body></html>\n"
    )
    print("wrote", DST)


if __name__ == "__main__":
    main()
