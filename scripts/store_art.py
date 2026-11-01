from pathlib import Path
import base64,subprocess
from xml.sax.saxutils import escape
out=Path('store');out.mkdir(exist_ok=True)
subprocess.run(['rsvg-convert','art/source/icon.svg','-o',str(out/'icon.png')],check=True)
encoded=base64.b64encode(Path('art/source/library-still-life.png').read_bytes()).decode()
for locale,headline,subtitle in [('en-US',['Your pens.','Your library.'],'Manage books over USB'),('de-DE',['Deine Stifte.','Deine Bücher.'],'Bücher per USB verwalten'),('fr-FR',['Vos stylets.','Vos livres.'],'Gérez vos livres par USB')]:
 dest=out/locale;dest.mkdir(exist_ok=True)
 svg=f'''<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="1024" height="500" viewBox="0 0 1024 500"><rect width="1024" height="500" fill="#F6F8F5"/><image width="1024" height="500" xlink:href="data:image/png;base64,{encoded}"/><g font-family="Arial, sans-serif" fill="#16483E"><text x="38" y="95" font-size="25" font-weight="700">Pen Library</text><text x="38" y="164" font-size="40" font-weight="700">{escape(headline[0])}</text><text x="38" y="212" font-size="40" font-weight="700">{escape(headline[1])}</text><text x="38" y="255" font-size="19">{escape(subtitle)}</text></g></svg>'''
 # SVGs are regenerated from the checked-in source bitmap and these strings.
 temp=Path('.local/store-art')/f'feature-{locale}.svg';temp.parent.mkdir(parents=True,exist_ok=True);temp.write_text(svg)
 subprocess.run(['rsvg-convert',str(temp),'-o',str(dest/'feature.png')],check=True)
