"""Validate the bundled catalog without printing keys or user location history."""
from pathlib import Path
from collections import Counter
import hashlib
import json
import math

root = Path(__file__).resolve().parents[1]
path = root / 'app/src/main/assets/catalog/legacy/landmarks.json'
raw = path.read_bytes()
data = json.loads(raw.decode('utf-8-sig'))
assert len(data) == 897, 'Expected the supplied 897-place catalog'
assert len({item['id'] for item in data}) == len(data), 'Duplicate ID'
types = {'NATURE','LANDMARK','HISTORY','ATTRACTION','BRIDGE','TUNNEL','PASS','COASTAL_ROAD','REST_AREA'}
for item in data:
    assert all(isinstance(item[key], str) and item[key].strip() for key in ['id','name','region','type'])
    assert item['type'] in types
    assert math.isfinite(item['lat']) and 33 <= item['lat'] <= 39
    assert math.isfinite(item['lon']) and 124 <= item['lon'] <= 132
    assert math.isfinite(item['radiusM']) and 0 < item['radiusM'] <= 5000
print(json.dumps({'count':len(data), 'sha256':hashlib.sha256(raw).hexdigest(),
                  'types':Counter(item['type'] for item in data),
                  'regions':Counter(item['region'] for item in data)}, ensure_ascii=True, indent=2))
