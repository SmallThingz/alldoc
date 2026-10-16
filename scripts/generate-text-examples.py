#!/usr/bin/env python3
"""Regenerate the authored Markdown, HTML and FictionBook examples using Python 3."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'app/src/main/assets/examples/text'
ENTRIES = []


def group(extensions, title, content, category='Documents', mime='text/markdown', description=''):
    for extension in extensions.split():
        name = title.lower().replace(' ', '-') + '.' + extension
        (OUT / name).write_text(content, encoding='utf-8')
        ENTRIES.append(dict(id='text-' + extension, title=title, asset='examples/text/' + name,
                            mime=mime, extension=extension, description=description, category=category))


def examples():
    group('md markdown', 'Trail guide', """# AllDoc trail guide

Walk the coastal path from **North cove** to the lighthouse. Allow *two hours* for the return trip.

## Before you leave

- Pack water and a notebook.
- Check the tide and weather.
- Keep to the marked path.

> Leave the shore as you found it.

## Route

1. Follow the boardwalk past the tide pools.
2. Turn inland at the lookout.
3. Stop at the lighthouse before heading back.

The trail marker reads `NC-05`. Keep this route note with your map:

```text
North cove    0 km
Lookout      2 km
Lighthouse   5 km
```

## Field notes

A quiet place to read: café, 日本語, नमस्ते, 🌱 and 🇦🇺.

### At the lookout

The sea was calm and the path was clear. We watched the birds settle on the rocks, then opened our notebooks.

### At the lighthouse

Take a break before the return walk. **Check the tide again** if you plan to cross the beach.
""", description='Headings, emphasis, lists and code in one continuous document')
    body = '<h1>AllDoc coastal guide</h1><p>Explore the shoreline from North cove to the lighthouse.</p><h2>Before you leave</h2><ul><li>Check the tide</li><li>Pack water</li></ul><table><tr><th>Stop</th><th>Distance</th></tr><tr><td>Lighthouse</td><td>5 km</td></tr></table>'
    group('html htm', 'Coastal guide', '<!DOCTYPE html>\n<html lang="en"><head><meta charset="utf-8"><title>AllDoc coastal guide</title></head><body>'+body+'</body></html>\n', 'Documents', 'text/html')
    group('xhtml', 'Coastal guide', '<?xml version="1.0" encoding="UTF-8"?>\n<html xmlns="http://www.w3.org/1999/xhtml"><head><title>AllDoc coastal guide</title></head><body>'+body+'</body></html>\n', 'Documents', 'application/xhtml+xml')
    group('fb2', 'Coastal story', '<?xml version="1.0" encoding="UTF-8"?>\n<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info><genre>short_story</genre><author><first-name>AllDoc</first-name><last-name>Examples</last-name></author><book-title>Coastal story</book-title><lang>en</lang></title-info><document-info><author><nickname>AllDoc</nickname></author><date value="2026-10-15">15 October 2026</date><id>alldoc-coastal-story</id><version>1.0</version></document-info></description><body><title><p>AllDoc coastal story</p></title><section><title><p>North cove</p></title><p>The tide had left small pools between the rocks. We paused to watch the birds before walking to the lighthouse.</p></section></body></FictionBook>\n', 'Books', 'application/x-fictionbook+xml')


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    examples()
    manifest = ROOT / 'scripts/fixtures/examples-text.json'
    old = json.loads(manifest.read_text()) if manifest.exists() else {'examples': []}
    retained = {entry['asset'] for entry in ENTRIES}
    for entry in old['examples']:
        if entry['asset'] not in retained:
            asset = ROOT / 'app/src/main/assets' / entry['asset']
            if asset.parent != OUT or asset.is_symlink():
                raise ValueError('Unexpected old example path: ' + str(asset))
            asset.unlink(missing_ok=True)
    manifest.write_text(json.dumps({'version': 1, 'examples': ENTRIES}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'examples': len(ENTRIES), 'bytes': sum((ROOT / 'app/src/main/assets' / entry['asset']).stat().st_size for entry in ENTRIES)}))


if __name__ == '__main__':
    main()
