"""
Prepara o aplicativo com o nome, o site e o icone escolhidos.
Le as variaveis NOME, SITE e ICONE (opcional) e gera:
  - o nome e o endereco dentro do app
  - a identidade (pacote) do app, a partir do nome
  - os icones do celular e o banner da TV box
"""
import io
import os
import re
import sys
import unicodedata
import urllib.parse
import urllib.request
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

RAIZ = Path(__file__).resolve().parent.parent
RES = RAIZ / "app" / "src" / "main" / "res"

PALAVRAS_JAVA = set("""abstract assert boolean break byte case catch char class const continue default do
double else enum extends final finally float for goto if implements import instanceof int interface long
native new package private protected public return short static strictfp super switch synchronized this
throw throws transient try void volatile while true false null var record yield""".split())

CORES = ["#E53935", "#8E24AA", "#3949AB", "#1E88E5", "#00897B", "#43A047", "#F4511E", "#6D4C41"]


def sem_acentos(texto):
    return unicodedata.normalize("NFKD", texto).encode("ascii", "ignore").decode("ascii")


def criar_pacote(nome):
    slug = re.sub(r"[^a-z0-9]", "", sem_acentos(nome).lower())
    if not slug:
        slug = "meuaplicativo"
    if slug[0].isdigit():
        slug = "app" + slug
    if slug in PALAVRAS_JAVA:
        slug += "app"
    return slug[:40], "app.site." + slug[:40]


def texto_android(valor):
    valor = valor.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"')
    valor = valor.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    if valor[:1] in ("@", "?"):
        valor = "\\" + valor
    return valor


def baixar_imagem(url):
    try:
        pedido = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(pedido, timeout=15) as r:
            dados = r.read()
        img = Image.open(io.BytesIO(dados))
        img.load()
        img = img.convert("RGBA")
        if img.width < 32 or img.height < 32:
            return None
        return img
    except Exception as erro:
        print(f"  (nao consegui usar a imagem {url}: {erro})")
        return None


def achar_icone(site, icone):
    if icone:
        img = baixar_imagem(icone)
        if img:
            return img
    dominio = urllib.parse.urlparse(site).netloc
    if not dominio:
        return None
    for url in (
        f"https://www.google.com/s2/favicons?domain={dominio}&sz=256",
        f"https://icons.duckduckgo.com/ip3/{dominio}.ico",
    ):
        img = baixar_imagem(url)
        if img:
            return img
    return None


def fonte(tamanho):
    for caminho in (
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
    ):
        if os.path.exists(caminho):
            return ImageFont.truetype(caminho, tamanho)
    try:
        return ImageFont.load_default(size=tamanho)
    except TypeError:
        return ImageFont.load_default()


def desenhar_letra(lado, nome, cor):
    img = Image.new("RGBA", (lado, lado), cor)
    d = ImageDraw.Draw(img)
    letra = (sem_acentos(nome).strip()[:1] or "A").upper()
    f = fonte(int(lado * 0.55))
    caixa = d.textbbox((0, 0), letra, font=f)
    l, a = caixa[2] - caixa[0], caixa[3] - caixa[1]
    d.text(((lado - l) / 2 - caixa[0], (lado - a) / 2 - caixa[1]), letra, font=f, fill="white")
    return img


def montar_icone(lado, imagem, nome, cor):
    """Quadrado de cantos arredondados com o icone do site (ou a primeira letra)."""
    if imagem is not None:
        base = Image.new("RGBA", (lado, lado), "white")
        interno = int(lado * 0.72)
        logo = imagem.copy()
        logo.thumbnail((interno, interno), Image.LANCZOS)
        if logo.width < interno and logo.height < interno:
            escala = interno / max(logo.width, logo.height)
            logo = logo.resize((round(logo.width * escala), round(logo.height * escala)), Image.LANCZOS)
        base.alpha_composite(logo, ((lado - logo.width) // 2, (lado - logo.height) // 2))
    else:
        base = desenhar_letra(lado, nome, cor)
    mascara = Image.new("L", (lado, lado), 0)
    ImageDraw.Draw(mascara).rounded_rectangle((0, 0, lado - 1, lado - 1), radius=int(lado * 0.22), fill=255)
    final = Image.new("RGBA", (lado, lado), (0, 0, 0, 0))
    final.paste(base, (0, 0), mascara)
    return final


def montar_banner(icone_grande, nome, cor):
    """Banner 320x180 que aparece no menu da TV box."""
    r, g, b = (int(cor[i:i + 2], 16) for i in (1, 3, 5))
    banner = Image.new("RGBA", (320, 180), (int(r * 0.6), int(g * 0.6), int(b * 0.6), 255))
    ic = icone_grande.resize((112, 112), Image.LANCZOS)
    banner.alpha_composite(ic, (20, 34))
    d = ImageDraw.Draw(banner)
    texto = nome.strip()
    tamanho = 30
    f = fonte(tamanho)
    while tamanho > 14 and d.textlength(texto, font=f) > 160:
        tamanho -= 2
        f = fonte(tamanho)
    while d.textlength(texto, font=f) > 160 and len(texto) > 1:
        texto = texto[:-1]
    caixa = d.textbbox((0, 0), texto, font=f)
    d.text((148, 90 - (caixa[3] - caixa[1]) / 2 - caixa[1]), texto, font=f, fill="white")
    return banner.convert("RGB")


def main():
    nome = (os.environ.get("NOME") or "").strip()
    site = (os.environ.get("SITE") or "").strip()
    icone = (os.environ.get("ICONE") or "").strip()
    if not nome or not site:
        sys.exit("Faltou o NOME ou o SITE.")
    if not re.match(r"^https?://", site, re.I):
        site = "https://" + site

    slug, pacote = criar_pacote(nome)
    cor = CORES[sum(map(ord, slug)) % len(CORES)]
    print(f"Nome: {nome}\nSite: {site}\nPacote: {pacote}")

    (RES / "values").mkdir(parents=True, exist_ok=True)
    (RES / "values" / "config.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
        f'    <string name="app_name">{texto_android(nome)}</string>\n'
        f'    <string name="site" translatable="false">{texto_android(site)}</string>\n'
        "</resources>\n",
        encoding="utf-8",
    )
    (RAIZ / "config.properties").write_text(f"pacote={pacote}\n", encoding="utf-8")

    imagem = achar_icone(site, icone)
    print("Icone:", "imagem do site/link" if imagem else "primeira letra do nome")
    for pasta, lado in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        destino = RES / f"mipmap-{pasta}"
        destino.mkdir(parents=True, exist_ok=True)
        montar_icone(lado, imagem, nome, cor).save(destino / "ic_launcher.png")
    (RES / "drawable-xhdpi").mkdir(parents=True, exist_ok=True)
    montar_banner(montar_icone(256, imagem, nome, cor), nome, cor).save(RES / "drawable-xhdpi" / "banner.png")

    saida = os.environ.get("GITHUB_OUTPUT")
    if saida:
        with open(saida, "a", encoding="utf-8") as f:
            f.write(f"slug={slug}\n")


if __name__ == "__main__":
    main()
