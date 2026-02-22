"""
CLI tool for converting Markdown files to XML format.

Main entry point with argument parsing and error handling.
"""

import sys
import argparse
from pathlib import Path
from typing import Optional

from md_parser import parse_markdown_file
from xml_builder import XMLBuilder


def parse_arguments() -> argparse.Namespace:
    """
    Parse command-line arguments.
    
    Returns:
        Parsed arguments
    """
    parser = argparse.ArgumentParser(
        description="Convert Markdown files to XML format",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python main.py input.md output.xml
  python main.py docs/readme.md docs/readme.xml
        """
    )
    
    parser.add_argument(
        "input",
        type=str,
        help="Path to input Markdown file"
    )
    
    parser.add_argument(
        "output",
        type=str,
        help="Path to output XML file"
    )
    
    parser.add_argument(
        "-v", "--verbose",
        action="store_true",
        help="Enable verbose output"
    )
    
    return parser.parse_args()


def validate_input_file(file_path: str) -> Path:
    """
    Validate that input file exists and is readable.
    
    Args:
        file_path: Path to input file
        
    Returns:
        Validated Path object
        
    Raises:
        FileNotFoundError: If file does not exist
        PermissionError: If file is not readable
    """
    path = Path(file_path)
    
    if not path.exists():
        raise FileNotFoundError(f"Input file does not exist: {file_path}")
    
    if not path.is_file():
        raise ValueError(f"Input path is not a file: {file_path}")
    
    if not path.suffix.lower() == ".md":
        raise ValueError(f"Input file must be a Markdown file (.md): {file_path}")
    
    # Try to open file to verify readability
    try:
        with open(path, "r", encoding="utf-8") as f:
            pass
    except PermissionError:
        raise PermissionError(f"Input file is not readable: {file_path}")
    except Exception:
        raise PermissionError(f"Cannot access input file: {file_path}")
    
    return path


def validate_output_path(file_path: str) -> Path:
    """
    Validate that output path is writable.
    
    Args:
        file_path: Path to output file
        
    Returns:
        Validated Path object
        
    Raises:
        PermissionError: If output path is not writable
    """
    path = Path(file_path)
    
    # Check if parent directory exists and is writable
    parent = path.parent
    if not parent.exists():
        try:
            parent.mkdir(parents=True, exist_ok=True)
        except Exception as e:
            raise PermissionError(f"Cannot create output directory: {e}")
    
    if not parent.is_dir():
        raise ValueError(f"Output directory path is not a directory: {parent}")
    
    if not parent.exists() or not parent.is_dir():
        raise PermissionError(f"Output directory does not exist or is not writable: {parent}")
    
    return path


def main() -> int:
    """
    Main CLI entry point.
    
    Returns:
        Exit code (0 for success, non-zero for error)
    """
    args = parse_arguments()
    
    try:
        # Validate input file
        input_path = validate_input_file(args.input)
        if args.verbose:
            print(f"✓ Input file validated: {input_path}")
        
        # Validate output path
        output_path = validate_output_path(args.output)
        if args.verbose:
            print(f"✓ Output directory validated: {output_path.parent}")
        
        # Parse markdown file
        if args.verbose:
            print(f"→ Parsing markdown file: {input_path}")
        sections = parse_markdown_file(str(input_path))
        if args.verbose:
            print(f"✓ Found {len(sections)} sections")
            for i, section in enumerate(sections, 1):
                print(f"  Section {i}: Level {section.heading_level} - '{section.heading_text}'")
        
        # Build and write XML
        if args.verbose:
            print(f"→ Building XML...")
        builder = XMLBuilder()
        builder.add_sections(sections)
        builder.write_xml(str(output_path))
        if args.verbose:
            print(f"✓ XML written to: {output_path}")
        
        print(f"Success: Converted {len(sections)} sections from '{args.input}' to '{args.output}'")
        return 0
    
    except FileNotFoundError as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1
    except PermissionError as e:
        print(f"Permission Error: {e}", file=sys.stderr)
        return 1
    except ValueError as e:
        print(f"Validation Error: {e}", file=sys.stderr)
        return 1
    except IOError as e:
        print(f"IO Error: {e}", file=sys.stderr)
        return 1
    except Exception as e:
        print(f"Unexpected error: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
