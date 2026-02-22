"""Utility functions for markdown processing and XML tag normalization."""

import re
from typing import Optional


def normalize_tag_name(text: str) -> str:
    """
    Normalize text to be a valid XML tag name.
    
    Rules for XML tag names:
    - Cannot contain spaces (replace with underscores)
    - Cannot contain special characters (remove or replace)
    - Cannot start with a number (prepend underscore if needed)
    - Cannot start with 'xml' (case-insensitive)
    - Must not be empty
    
    Args:
        text: Raw tag name text from markdown heading
        
    Returns:
        Normalized tag name suitable for XML
    """
    if not text:
        return "section"
    
    # Strip whitespace from beginning and end
    text = text.strip()
    
    if not text:
        return "section"
    
    # Replace spaces with underscores
    text = text.replace(" ", "_")
    
    # Replace hyphens with underscores (optional, but common)
    text = text.replace("-", "_")
    
    # Remove or replace special characters, keeping only alphanumeric, underscores, and dots
    # Keep dots as they're allowed in XML tag names
    text = re.sub(r"[^a-zA-Z0-9_.\-]", "", text)
    
    # Remove multiple consecutive underscores
    text = re.sub(r"_+", "_", text)
    
    # Remove leading/trailing underscores or dots
    text = text.strip("_.")
    
    # If starts with a digit, prepend underscore
    if text and text[0].isdigit():
        text = "_" + text
    
    # Lowercase (optional but common for XML)
    text = text.lower()
    
    # Check for reserved 'xml' prefix (case-insensitive)
    if text.lower().startswith("xml"):
        text = "_" + text
    
    # If empty after processing, use default
    if not text:
        return "section"
    
    return text


def strip_markdown_heading(line: str) -> tuple[int, Optional[str]]:
    """
    Parse a markdown heading line and extract level and text.
    
    Args:
        line: A line that may start with markdown heading markers
        
    Returns:
        Tuple of (heading_level, heading_text) or (0, None) if not a heading
    """
    stripped = line.lstrip()
    
    # Count leading # symbols
    hash_count = 0
    for char in stripped:
        if char == "#":
            hash_count += 1
        else:
            break
    
    # Valid markdown headings have 1-4 hashes followed by space
    if hash_count == 0 or hash_count > 4:
        return 0, None
    
    # Must have space after hashes (or immediate text with no space is invalid)
    if len(stripped) <= hash_count:
        return 0, None
    
    if stripped[hash_count] != " ":
        return 0, None
    
    # Extract text content after the hashes and space
    text = stripped[hash_count + 1:].strip()
    
    return hash_count, text if text else None


def escape_xml_content(text: str) -> str:
    """
    Escape special XML characters in text content.
    
    Args:
        text: Raw text content
        
    Returns:
        XML-escaped content
    """
    replacements = {
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        '"': "&quot;",
        "'": "&apos;",
    }
    
    result = text
    for char, escaped in replacements.items():
        result = result.replace(char, escaped)
    
    return result
