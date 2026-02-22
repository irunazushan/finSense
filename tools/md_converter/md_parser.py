"""Markdown parser for extracting sections with headers and content."""

from dataclasses import dataclass
from typing import List, Optional
from utils import strip_markdown_heading


@dataclass
class Section:
    """Represents a markdown section with heading and content."""
    
    heading_level: int
    heading_text: str
    content: str
    
    def __post_init__(self):
        """Validate section data."""
        if not self.heading_text:
            raise ValueError("Heading text cannot be empty")
        if self.heading_level < 1 or self.heading_level > 4:
            raise ValueError(f"Heading level must be 1-4, got {self.heading_level}")


class MarkdownParser:
    """Parser for converting markdown files into structured sections."""
    
    def __init__(self, content: str):
        """
        Initialize parser with markdown content.
        
        Args:
            content: Full markdown file content as string
        """
        self.lines = content.split("\n")
        self.sections: List[Section] = []
    
    def _is_code_fence(self, line: str) -> bool:
        """
        Check if line is a code fence marker (``` or ~~~).
        
        Args:
            line: Line to check
            
        Returns:
            True if line starts with code fence marker
        """
        stripped = line.strip()
        return stripped.startswith("```") or stripped.startswith("~~~")
    
    def parse(self) -> List[Section]:
        """
        Parse markdown content and extract sections.
        
        Process:
        1. Find all lines that are headings (start with # ## ### ####)
        2. Ignore headings inside code blocks (between ``` or ~~~)
        3. For each heading, collect all content until the next heading
        4. Content is stripped of leading/trailing empty lines
        5. Return list of Section objects
        
        Returns:
            List of Section objects representing markdown structure
        """
        self.sections = []
        i = 0
        in_code_block = False
        
        while i < len(self.lines):
            line = self.lines[i]
            
            # Track code blocks - toggle on ``` or ~~~
            if self._is_code_fence(line):
                in_code_block = not in_code_block
            
            # Only check for headings outside code blocks
            heading_level, heading_text = (0, None) if in_code_block else strip_markdown_heading(line)
            
            if heading_level > 0 and heading_text:
                # Found a heading, collect content until next heading
                i += 1
                content_lines = []
                in_content_code_block = False
                
                while i < len(self.lines):
                    current_line = self.lines[i]
                    
                    # Track code blocks in content
                    if self._is_code_fence(current_line):
                        in_content_code_block = not in_content_code_block
                    
                    # Only check for next heading if NOT in code block
                    next_heading_level = 0
                    if not in_content_code_block:
                        next_heading_level, _ = strip_markdown_heading(current_line)
                    
                    if next_heading_level > 0:
                        # Found next heading, stop collecting content
                        break
                    
                    content_lines.append(current_line)
                    i += 1
                
                # Process content: join lines and strip empty lines from start/end
                content = "\n".join(content_lines).strip()
                
                # Create section with processed content
                section = Section(
                    heading_level=heading_level,
                    heading_text=heading_text,
                    content=content
                )
                self.sections.append(section)
            else:
                i += 1
        
        return self.sections


def parse_markdown_file(file_path: str) -> List[Section]:
    """
    Convenience function to parse markdown file from path.
    
    Args:
        file_path: Path to markdown file
        
    Returns:
        List of parsed Section objects
        
    Raises:
        FileNotFoundError: If file does not exist
        ValueError: If file is not valid markdown or parsing fails
    """
    try:
        with open(file_path, "r", encoding="utf-8") as f:
            content = f.read()
    except FileNotFoundError:
        raise FileNotFoundError(f"Markdown file not found: {file_path}")
    except Exception as e:
        raise ValueError(f"Error reading markdown file: {e}")
    
    parser = MarkdownParser(content)
    return parser.parse()
