"""XML builder for converting markdown sections to XML output."""

from typing import List
from xml.dom import minidom
from md_parser import Section
from utils import normalize_tag_name, escape_xml_content


class XMLBuilder:
    """Builds XML document from markdown sections."""
    
    def __init__(self):
        """Initialize XML builder."""
        self.sections: List[Section] = []
    
    def add_sections(self, sections: List[Section]) -> None:
        """
        Add sections to be converted to XML.
        
        Args:
            sections: List of Section objects to convert
        """
        self.sections = sections
    
    def build_xml(self) -> str:
        """
        Build XML string from sections.
        
        Each section is converted to:
        <tag_name>
        {section_content}
        </tag_name>
        
        Tag names are normalized from section heading text.
        Content is XML-escaped.
        
        Returns:
            Formatted XML string
        """
        xml_parts = []
        xml_parts.append('<?xml version="1.0" encoding="UTF-8"?>')
        xml_parts.append("<root>")
        
        for section in self.sections:
            tag_name = normalize_tag_name(section.heading_text)
            escaped_content = escape_xml_content(section.content)
            
            # Format with proper indentation
            xml_parts.append(f"  <{tag_name}>")
            
            # Add content with proper indentation
            if escaped_content:
                for line in escaped_content.split("\n"):
                    xml_parts.append(f"    {line}")
            
            xml_parts.append(f"  </{tag_name}>")
        
        xml_parts.append("</root>")
        
        return "\n".join(xml_parts)
    
    def write_xml(self, output_path: str) -> None:
        """
        Build and write XML to file.
        
        Args:
            output_path: Path where XML file will be written
            
        Raises:
            IOError: If file cannot be written
            ValueError: If no sections available to write
        """
        if not self.sections:
            raise ValueError("No sections to write to XML")
        
        xml_content = self.build_xml()
        
        try:
            with open(output_path, "w", encoding="utf-8") as f:
                f.write(xml_content)
        except Exception as e:
            raise IOError(f"Error writing XML file: {e}")


def sections_to_xml(sections: List[Section]) -> str:
    """
    Convenience function to convert sections to XML string.
    
    Args:
        sections: List of Section objects
        
    Returns:
        XML string representation
    """
    builder = XMLBuilder()
    builder.add_sections(sections)
    return builder.build_xml()
