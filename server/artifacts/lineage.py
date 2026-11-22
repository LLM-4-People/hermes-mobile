"""Use Hermes's persistence reader to name one owner's stable shelf and its storage generations."""
from pathlib import Path


def session_segments(home, session):
    from persistence_assembly import open_persistence_reader

    with open_persistence_reader(Path(home)) as db:
        lineage = db.lineage.lineage(session)
        if not lineage:
            return ()
        # A fork is the root of its own shelf. The forward compression walk also includes
        # later generations of a fork that has itself since compacted.
        root = str(lineage[0])
        return tuple(dict.fromkeys([root, *map(str, lineage), *map(str, db.lineage.chain(root))]))
