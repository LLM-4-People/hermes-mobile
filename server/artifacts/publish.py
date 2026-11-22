"""Publish one output from the current workspace. No HTTP upload or path-based download."""
import argparse
import os
from pathlib import Path

if __package__:
    from .store import publish
else:
    from store import publish


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('file', help='Workspace-relative path to the completed file')
    parser.add_argument('--session', required=True, help='Destination Hermes session ID')
    parser.add_argument('--name', help='Optional display name')
    args = parser.parse_args()
    home = os.environ.get('HERMES_HOME')
    if not home:
        parser.error('Set HERMES_HOME to the active profile home')
    try:
        print(publish(home, args.session, Path.cwd(), args.file, args.name))
    except (ValueError, OSError) as exc:
        parser.exit(1, f'Publication failed ({type(exc).__name__}).\n')


if __name__ == '__main__':
    main()
