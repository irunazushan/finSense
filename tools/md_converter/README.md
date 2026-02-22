# MD Converter - Markdown to XML Converter

CLI utility for converting Markdown files to XML format. Transforms markdown sections (headings and content) into structured XML with proper tag naming and content escaping.

## Features

- ✅ Converts markdown headings (# ## ### ####) to XML tags
- ✅ Handles multi-level heading hierarchy
- ✅ Automatically normalizes XML tag names (spaces, special characters)
- ✅ Properly escapes XML content
- ✅ Handles empty lines and EOF correctly
- ✅ Production-ready error handling
- ✅ Verbose output mode for debugging
- ✅ Clean, extensible architecture

## Project Structure

```
md_converter/
├── main.py           # CLI entry point with argparse
├── md_parser.py      # Markdown parsing logic
├── xml_builder.py    # XML generation logic
├── utils.py          # Utility functions (tag normalization, escaping)
├── requirements.txt  # Project dependencies
├── README.md         # This file
└── examples/         # Example files
    ├── example.md    # Example input markdown
    └── example.xml   # Expected output XML
```

## Requirements

- Python 3.11 or higher
- No external dependencies (uses only Python stdlib)

## Installation

```bash
cd tools/md_converter
```

## Usage

### Basic Usage

```bash
python main.py input.md output.xml
```

### With Verbose Output

```bash
python main.py input.md output.xml -v
# or
python main.py input.md output.xml --verbose
```

### Examples

```bash
# Convert a single file
python main.py docs/readme.md docs/readme.xml

# Convert with verbose output
python main.py input.md output.xml -v
```

## How It Works

### Input Processing

1. **Markdown Parsing**: Reads markdown file and identifies headings (1-4 levels)
2. **Section Extraction**: Collects all content until the next heading of any level
3. **Content Cleanup**: Strips leading/trailing empty lines from sections

### XML Generation

1. **Tag Normalization**: Converts heading text to valid XML tag names
   - Replaces spaces with underscores
   - Removes special characters
   - Converts to lowercase
   - Handles edge cases (leading digits, reserved prefixes)

2. **Content Escaping**: Properly escapes XML special characters
   - `&` → `&amp;`
   - `<` → `&lt;`
   - `>` → `&gt;`
   - `"` → `&quot;`
   - `'` → `&apos;`

3. **XML Building**: Generates well-formed XML with proper indentation

### Error Handling

- File validation (existence, permissions, format)
- Directory creation if output path doesn't exist
- Clear error messages with specific details
- Proper exit codes (0 = success, 1 = error, 2 = unexpected)

## Example Conversion

### Input (input.md)

```markdown
## kafka connection
some text about connection

## kafka settings
some settings text
```

### Output (output.xml)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<root>
  <kafka_connection>
    some text about connection
  </kafka_connection>
  <kafka_settings>
    some settings text
  </kafka_settings>
</root>
```

## Tag Normalization Examples

| Input Text       | Output Tag Name |
|-----------------|-----------------|
| `kafka connection` | `kafka_connection` |
| `API Settings`   | `api_settings` |
| `Docs & Guides`  | `docs_guides` |
| `3rd-Party Code` | `_3rd_party_code` |
| `test@example`   | `testexample` |

## Architecture

### Module Breakdown

**utils.py**
- `normalize_tag_name()`: Normalizes heading text to valid XML tags
- `strip_markdown_heading()`: Parses markdown heading syntax
- `escape_xml_content()`: Escapes XML special characters

**md_parser.py**
- `Section`: Dataclass representing a markdown section
- `MarkdownParser`: Parses markdown content into sections
- `parse_markdown_file()`: Convenience function for file-based parsing

**xml_builder.py**
- `XMLBuilder`: Converts sections to XML
- `sections_to_xml()`: Convenience function for direct conversion

**main.py**
- CLI argument parsing with argparse
- Input/output validation
- Error handling and user feedback
- Main orchestration logic

## Error Handling

The tool handles various error scenarios:

```
FileNotFoundError     → Input file not found (exit code 1)
PermissionError       → Cannot read/write files (exit code 1)
ValueError            → Invalid file type or parameters (exit code 1)
IOError               → File I/O issues (exit code 1)
Exception             → Unexpected errors (exit code 2)
```

## Testing

Run with verbose flag to see detailed processing:

```bash
python main.py example.md example.xml -v
```

Output:
```
✓ Input file validated: example.md
✓ Output directory validated: .
→ Parsing markdown file: example.md
✓ Found 2 sections
  Section 1: Level 2 - 'kafka connection'
  Section 2: Level 2 - 'kafka settings'
→ Building XML...
✓ XML written to: example.xml
Success: Converted 2 sections from 'example.md' to 'example.xml'
```

## Development

The codebase is designed for extensibility:

- **New output formats**: Extend `XMLBuilder` or create `JsonBuilder` class
- **Custom tag naming**: Modify `normalize_tag_name()` function
- **Additional transformations**: Add methods to `Section` dataclass
- **Content processing**: Extend parsing logic in `MarkdownParser`

## License

Internal project
