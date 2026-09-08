"""Generate display-only regional names from the upstream BCData cache."""
import csv
import sys
from pathlib import Path
from xml.sax.saxutils import escape

cache = Path(sys.argv[1])
root = Path(__file__).resolve().parents[1]
arrays = []
for region, lang in [('en', 'en'), ('tw', 'tw'), ('jp', 'ja'), ('kr', 'ko')]:
    names = []
    for index in range(876):
        path = cache / region / '15.5.0' / 'resLocal' / f'Unit_Explanation{index + 1}_{lang}.csv'
        forms = []
        if path.is_file():
            with path.open(encoding='utf-8-sig', newline='') as stream:
                forms = [row[0].strip() if row else '' for row in csv.reader(stream, delimiter=',' if region == 'jp' else '|')][:4]
        value = escape(' / '.join(forms)).replace('\\', '\\\\').replace("'", "\\'").replace('"', '\\"')
        names.append('        <item>"' + value + '"</item>')
    arrays.append(f'    <string-array name="cat_names_{region}">\n' + '\n'.join(names) + '\n    </string-array>')
block = '\n'.join(arrays)
for locale in ['values', 'values-zh-rCN']:
    path = root / 'app/src/main/res' / locale / 'strings.xml'
    source = path.read_text()
    start = source.find('    <!-- BEGIN GENERATED CAT NAMES -->')
    if start >= 0:
        end = source.index('    <!-- END GENERATED CAT NAMES -->', start) + len('    <!-- END GENERATED CAT NAMES -->\n')
        source = source[:start] + source[end:]
    source = source.replace('</resources>', '    <!-- BEGIN GENERATED CAT NAMES -->\n' + block + '\n    <!-- END GENERATED CAT NAMES -->\n</resources>')
    path.write_text(source)
