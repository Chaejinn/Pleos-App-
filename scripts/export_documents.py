"""Export the implementation-aligned Korean documents; no network or API keys are read."""
from pathlib import Path
import re
import sys
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / '.local' / 'pdf-tools'))
from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, PageBreak, Table, TableStyle, Preformatted

FONT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('C:/Windows/Fonts/malgun.ttf')
BOLD = FONT.with_name('malgunbd.ttf')
if not FONT.exists():
    raise SystemExit('Pass a Korean TrueType font path: python scripts/export_documents.py <font.ttf>')
pdfmetrics.registerFont(TTFont('Korean', str(FONT)))
pdfmetrics.registerFont(TTFont('KoreanBold', str(BOLD if BOLD.exists() else FONT)))
pdfmetrics.registerFontFamily('Korean', normal='Korean', bold='KoreanBold', italic='Korean', boldItalic='KoreanBold')
INK = colors.HexColor('#173F35')
BODY = colors.HexColor('#263E38')
MUTED = colors.HexColor('#59746B')
styles = {
    'body': ParagraphStyle('body', fontName='Korean', fontSize=10, leading=16, textColor=BODY,
                           spaceAfter=7, wordWrap='CJK'),
    'h1': ParagraphStyle('h1', fontName='KoreanBold', fontSize=17, leading=25, textColor=INK,
                         spaceBefore=17, spaceAfter=9, keepWithNext=True, wordWrap='CJK'),
    'h2': ParagraphStyle('h2', fontName='KoreanBold', fontSize=12.5, leading=19, textColor=INK,
                         spaceBefore=12, spaceAfter=7, keepWithNext=True, wordWrap='CJK'),
    'table': ParagraphStyle('table', fontName='Korean', fontSize=8.8, leading=13.5, textColor=BODY, wordWrap='CJK'),
    'cover': ParagraphStyle('cover', fontName='KoreanBold', fontSize=29, leading=43, textColor=INK, wordWrap='CJK'),
    'subtitle': ParagraphStyle('subtitle', fontName='Korean', fontSize=13, leading=22, textColor=MUTED, wordWrap='CJK'),
    'code': ParagraphStyle('code', fontName='Korean', fontSize=8, leading=12, textColor=BODY),
}

def inline(text):
    text = re.sub(r'\[([^\]]+)\]\(([^)]+)\)', r'\1 (\2)', text)
    text = escape(text)
    text = re.sub(r'\*\*(.+?)\*\*', r'<b>\1</b>', text)
    return text.replace('`', '')

def decorate(canvas, document):
    canvas.saveState()
    canvas.setStrokeColor(colors.HexColor('#CFDED8'))
    canvas.line(20*mm, 19*mm, A4[0]-20*mm, 19*mm)
    canvas.setFont('Korean', 8)
    canvas.setFillColor(MUTED)
    canvas.drawString(20*mm, 13*mm, 'ROADLOG  ·  merge_jhmin  ·  2026.11.15')
    canvas.drawRightString(A4[0]-20*mm, 13*mm, str(document.page))
    if document.page > 1:
        canvas.setFont('KoreanBold', 8)
        canvas.drawString(20*mm, A4[1]-15*mm, document.title)
    canvas.restoreState()

def make_pdf(source, filename, title, subtitle):
    output = source.parent / filename
    doc = SimpleDocTemplate(str(output), pagesize=A4, rightMargin=20*mm, leftMargin=20*mm,
                            topMargin=24*mm, bottomMargin=26*mm, title=title,
                            author='RoadLog 팀', subject='현재 프로젝트 구현 및 검증 범위')
    story = [Spacer(1, 25*mm), Paragraph('ROADLOG', styles['subtitle']), Spacer(1, 10*mm),
             Paragraph(title, styles['cover']), Spacer(1, 12*mm), Paragraph(subtitle, styles['subtitle']),
             Spacer(1, 20*mm), Paragraph('전국 897개 랜드마크 · 카카오 지도 · 경로 기록 · 수집 도감', styles['body']),
             Paragraph('2026 현대자동차그룹 Pleos App Market 앱 개발 공모전', styles['body']),
             Paragraph('구현 기준 2026.11.15 / 원본 기획 v1.1과 기능명세 2026.11.11 반영', styles['body']),
             Spacer(1, 15*mm), Paragraph('이 문서는 현재 구현과 남은 검증을 구분합니다. 시·군·구 경계 배지와 랜드마크 반경 수집은 서로 다른 기능입니다.', styles['subtitle']),
             PageBreak()]
    lines = source.read_text(encoding='utf-8').splitlines()
    i = 0
    while i < len(lines):
        line = lines[i].strip()
        if not line or line == '---' or line.startswith('# '):
            i += 1
            continue
        if line.startswith('```'):
            block = []
            i += 1
            while i < len(lines) and not lines[i].strip().startswith('```'):
                block.append(lines[i]); i += 1
            story.append(Preformatted('\n'.join(block), styles['code'], maxLineLength=85))
        elif line.startswith('|'):
            rows = []
            while i < len(lines) and lines[i].strip().startswith('|'):
                cells = [cell.strip() for cell in lines[i].strip().strip('|').split('|')]
                if not all(re.fullmatch(r'[:\- ]+', cell) for cell in cells):
                    rows.append([Paragraph(inline(cell), styles['table']) for cell in cells])
                i += 1
            count = len(rows[0])
            weights = {2:[.27,.73],3:[.18,.36,.46],4:[.09,.24,.25,.42]}.get(count, [1/count]*count)
            table = Table(rows, colWidths=[doc.width*w for w in weights], repeatRows=1, hAlign='LEFT')
            table.setStyle(TableStyle([
                ('BACKGROUND',(0,0),(-1,0),colors.HexColor('#DDECE5')),
                ('ROWBACKGROUNDS',(0,1),(-1,-1),[colors.white,colors.HexColor('#F5F8F6')]),
                ('VALIGN',(0,0),(-1,-1),'TOP'),('LEFTPADDING',(0,0),(-1,-1),7),
                ('RIGHTPADDING',(0,0),(-1,-1),7),('TOPPADDING',(0,0),(-1,-1),7),
                ('BOTTOMPADDING',(0,0),(-1,-1),7),('LINEBELOW',(0,0),(-1,0),.7,INK),
                ('LINEBELOW',(0,1),(-1,-1),.25,colors.HexColor('#DFE7E3')),
            ]))
            story.extend([table, Spacer(1, 9)])
            continue
        elif line.startswith('### '):
            story.append(Paragraph(inline(line[4:]), styles['h2']))
        elif line.startswith('## '):
            story.append(Paragraph(inline(line[3:]), styles['h1']))
        elif line.startswith('- '):
            story.append(Paragraph('•  ' + inline(line[2:]), styles['body']))
        else:
            story.append(Paragraph(inline(line), styles['body']))
        i += 1
    doc.build(story, onFirstPage=decorate, onLaterPages=decorate)
    print(output.name)

if __name__ == '__main__':
    folder = ROOT / 'docs' / 'delivery'
    make_pdf(folder / 'PROJECT_PLAN.md', 'RoadLog_기획서.pdf', '로드로그 서비스 기획서',
             '매일의 이동을 나만의 방문 기록으로')
    make_pdf(folder / 'FUNCTIONAL_SPEC.md', 'RoadLog_기능명세서.pdf', '로드로그 기능명세서',
             '구현 동작 · 데이터 계약 · 검증 기준')
