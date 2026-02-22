"""
Test script for md_converter utility.

Run tests to validate parsing and XML generation.
"""

import sys
import os
from pathlib import Path

# Add parent directory to path for imports
sys.path.insert(0, str(Path(__file__).parent.parent))

from md_parser import parse_markdown_file, MarkdownParser
from xml_builder import XMLBuilder
from utils import normalize_tag_name, strip_markdown_heading, escape_xml_content


def test_tag_normalization():
    """Test tag name normalization."""
    print("Testing tag normalization...")
    
    test_cases = [
        ("kafka connection", "kafka_connection"),
        ("API Settings", "api_settings"),
        ("Docs & Guides", "docs_guides"),
        ("3rd-Party Code", "_3rd_party_code"),
        ("test@example", "testexample"),
        ("XML Processing", "_xml_processing"),  # xml prefix
        ("hello-world", "hello_world"),
        ("  spaces  ", "spaces"),
        ("Multiple   Spaces", "multiple_spaces"),
    ]
    
    for input_text, expected in test_cases:
        result = normalize_tag_name(input_text)
        status = "✓" if result == expected else "✗"
        print(f"  {status} '{input_text}' -> '{result}' (expected '{expected}')")
        assert result == expected, f"Mismatch: got {result}, expected {expected}"
    
    print("✓ All tag normalization tests passed!\n")


def test_heading_parsing():
    """Test markdown heading parsing."""
    print("Testing markdown heading parsing...")
    
    test_cases = [
        ("# Main Title", (1, "Main Title")),
        ("## Section", (2, "Section")),
        ("### Subsection", (3, "Subsection")),
        ("#### Deep Level", (4, "Deep Level")),
        ("##### Too Deep", (0, None)),  # Invalid
        ("No heading", (0, None)),
        ("##NoSpace", (0, None)),  # No space after #
        ("#", (0, None)),  # No text
        ("  ## Indented", (2, "Indented")),
    ]
    
    for input_text, expected in test_cases:
        result = strip_markdown_heading(input_text)
        match = result == expected
        status = "✓" if match else "✗"
        print(f"  {status} '{input_text}' -> {result}")
        assert match, f"Mismatch: got {result}, expected {expected}"
    
    print("✓ All heading parsing tests passed!\n")


def test_xml_escaping():
    """Test XML content escaping."""
    print("Testing XML content escaping...")
    
    test_cases = [
        ("Normal text", "Normal text"),
        ("Text with & ampersand", "Text with &amp; ampersand"),
        ("Less < than", "Less &lt; than"),
        ("Greater > than", "Greater &gt; than"),
        ('Quotes "double"', 'Quotes &quot;double&quot;'),
        ("Apostrophe 'single'", "Apostrophe &apos;single&apos;"),
        ("Complex & < > \" '", "Complex &amp; &lt; &gt; &quot; &apos;"),
    ]
    
    for input_text, expected in test_cases:
        result = escape_xml_content(input_text)
        match = result == expected
        status = "✓" if match else "✗"
        print(f"  {status} Escaping: {result[:40]}...")
        assert match, f"Mismatch: got {result}, expected {expected}"
    
    print("✓ All XML escaping tests passed!\n")


def test_markdown_parsing():
    """Test markdown parsing."""
    print("Testing markdown parsing...")
    
    markdown_content = """# Title

## Section One
Content of section one
Multiple lines here

## Section Two
Content of section two

### Subsection
More content


## Section Three
Final section
"""
    
    parser = MarkdownParser(markdown_content)
    sections = parser.parse()
    
    print(f"  ✓ Parsed {len(sections)} sections")
    assert len(sections) == 5, f"Expected 5 sections, got {len(sections)}"
    
    # Check first section (# Title)
    assert sections[0].heading_level == 1
    assert sections[0].heading_text == "Title"
    print(f"  ✓ Section 1: Level {sections[0].heading_level} - '{sections[0].heading_text}'")
    
    # Check second section (## Section One)
    assert sections[1].heading_level == 2
    assert sections[1].heading_text == "Section One"
    assert "Content of section one" in sections[1].content
    print(f"  ✓ Section 2: Level {sections[1].heading_level} - '{sections[1].heading_text}'")
    
    # Check nested section
    assert any("Subsection" in s.heading_text for s in sections), "Missing Subsection"
    print(f"  ✓ Found nested subsection")
    
    # Check empty lines are stripped
    assert not sections[1].content.startswith("\n"), "Leading newline not stripped"
    assert not sections[1].content.endswith("\n"), "Trailing newline not stripped"
    print(f"  ✓ Empty lines properly stripped")

    
    print("✓ All markdown parsing tests passed!\n")


def test_code_blocks():
    """Test that headings inside code blocks are ignored."""
    print("Testing code block handling...")
    
    markdown_content = """## Docker Compose

```yaml
## This is NOT a heading
# Neither is this one
```

More content here

## API Setup

```python
### Fake heading inside code
def my_function():
    pass
```

### Real Subsection
Real content
"""
    
    parser = MarkdownParser(markdown_content)
    sections = parser.parse()
    
    # Should find: Docker Compose, API Setup, Real Subsection (3 sections)
    # Should NOT find fake headings inside code blocks
    print(f"  ✓ Parsed {len(sections)} sections")
    assert len(sections) == 3, f"Expected 3 sections, got {len(sections)}"
    
    # Verify section names
    names = [s.heading_text for s in sections]
    assert "Docker Compose" in names, "Missing 'Docker Compose' section"
    assert "API Setup" in names, "Missing 'API Setup' section"
    assert "Real Subsection" in names, "Missing 'Real Subsection' section"
    
    # Verify that fake headings are in the content, not parsed as sections
    docker_section = next(s for s in sections if s.heading_text == "Docker Compose")
    assert "## This is NOT a heading" in docker_section.content
    assert "# Neither is this one" in docker_section.content
    print(f"  ✓ Fake headings inside code blocks are included in content")
    
    # Check API Setup contains the fake heading
    api_section = next(s for s in sections if s.heading_text == "API Setup")
    assert "### Fake heading inside code" in api_section.content
    print(f"  ✓ Code block content preserved correctly")
    
    print("✓ All code block tests passed!\n")


def test_xml_generation():
    """Test XML generation."""
    print("Testing XML generation...")
    
    markdown_content = """## kafka connection
Connection details here

## kafka settings
Setting values here"""
    
    parser = MarkdownParser(markdown_content)
    sections = parser.parse()
    
    builder = XMLBuilder()
    builder.add_sections(sections)
    xml = builder.build_xml()
    
    # Check XML structure
    assert '<?xml version="1.0"' in xml
    assert "<root>" in xml
    assert "</root>" in xml
    assert "<kafka_connection>" in xml
    assert "</kafka_connection>" in xml
    assert "<kafka_settings>" in xml
    assert "</kafka_settings>" in xml
    
    print(f"  ✓ Generated valid XML structure")
    print(f"  ✓ Found expected tags")
    
    print("✓ All XML generation tests passed!\n")


def run_file_test():
    """Test with actual example file."""
    print("Testing with example file...")
    
    example_path = Path(__file__).parent / "example.md"
    
    if example_path.exists():
        sections = parse_markdown_file(str(example_path))
        print(f"  ✓ Parsed example.md with {len(sections)} sections")
        
        builder = XMLBuilder()
        builder.add_sections(sections)
        xml = builder.build_xml()
        
        print(f"  ✓ Generated {len(xml)} characters of XML")
        
        # Verify specific sections exist
        assert "<kafka_connection>" in xml
        assert "<api_documentation>" in xml
        print(f"  ✓ Found expected sections in XML")
        
        print("✓ File test passed!\n")
    else:
        print(f"  ⚠ Example file not found: {example_path}\n")


def main():
    """Run all tests."""
    print("=" * 60)
    print("MD Converter - Test Suite")
    print("=" * 60)
    print()
    
    try:
        test_tag_normalization()
        test_heading_parsing()
        test_xml_escaping()
        test_markdown_parsing()
        test_code_blocks()
        test_xml_generation()
        run_file_test()
        
        print("=" * 60)
        print("✓ All tests passed successfully!")
        print("=" * 60)
        return 0
    
    except AssertionError as e:
        print(f"\n✗ Test failed: {e}")
        return 1
    except Exception as e:
        print(f"\n✗ Unexpected error: {e}")
        import traceback
        traceback.print_exc()
        return 1


if __name__ == "__main__":
    sys.exit(main())
