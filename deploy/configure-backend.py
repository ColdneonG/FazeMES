"""Enable the public MES module without enabling unrelated business modules."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
pom = root / 'backend/pom.xml'
text = pom.read_text(encoding='utf-8')
text = text.replace('<!--        <module>yudao-module-mes</module>-->', '        <module>yudao-module-mes</module>')
pom.write_text(text, encoding='utf-8')
server = root / 'backend/yudao-server/pom.xml'
text = server.read_text(encoding='utf-8')
pattern = r'<!--        <dependency>-->\s*<!--            <groupId>cn.iocoder.boot</groupId>-->\s*<!--            <artifactId>yudao-module-mes</artifactId>-->\s*<!--            <version>\$\{revision\}</version>-->\s*<!--        </dependency>-->'
text = re.sub(pattern, '''        <dependency>
            <groupId>cn.iocoder.boot</groupId>
            <artifactId>yudao-module-mes</artifactId>
            <version>${revision}</version>
        </dependency>''', text)
server.write_text(text, encoding='utf-8')
assert '<module>yudao-module-mes</module>' in pom.read_text(encoding='utf-8')
assert re.search(r'(?m)^\s*<artifactId>yudao-module-mes</artifactId>', text)
print('Enabled yudao-module-mes in parent and server POMs.')
