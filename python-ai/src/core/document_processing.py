from __future__ import annotations

from io import BytesIO
from pathlib import Path


def extract_text(
    filename: str,
    content: bytes | None,
    raw_text: str | None = None,
    legacy_doc_text: str | None = None,
) -> str:
    suffix = Path(filename or "").suffix.lower()
    if raw_text:
        return raw_text
    if suffix == ".doc":
        return legacy_doc_text or ""
    if content is None:
        return ""
    if suffix in {".txt", ".md"}:
        return content.decode("utf-8", errors="replace")
    if suffix == ".pdf":
        from pypdf import PdfReader

        reader = PdfReader(BytesIO(content))
        return "\n".join(page.extract_text() or "" for page in reader.pages)
    if suffix == ".docx":
        from docx import Document

        document = Document(BytesIO(content))
        return "\n".join(paragraph.text for paragraph in document.paragraphs)
    raise ValueError(f"Unsupported knowledge file type: {suffix}")
