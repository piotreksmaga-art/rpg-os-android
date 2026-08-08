from pathlib import Path
import math, random, re
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res/drawable-nodpi"
RES.mkdir(parents=True, exist_ok=True)
random.seed(132)

W, H = 512, 256

def curve_points(offset=0.0, amplitude=18.0, phase=0.0, samples=90):
    pts=[]
    for i in range(samples):
        t=i/(samples-1)
        x=18+t*(W-36)
        y=H*0.5 + offset + math.sin(t*math.pi*1.15+phase)*amplitude + math.sin(t*math.pi*3.0+phase)*3
        pts.append((x,y))
    return pts

def glow_line(points, core, glow, widths=(42,24,10,4)):
    out=Image.new("RGBA",(W,H),(0,0,0,0))
    glow_layer=Image.new("RGBA",(W,H),(0,0,0,0))
    gd=ImageDraw.Draw(glow_layer)
    gd.line(points, fill=glow, width=widths[0], joint="curve")
    glow_layer=glow_layer.filter(ImageFilter.GaussianBlur(16))
    out=Image.alpha_composite(out,glow_layer)
    d=ImageDraw.Draw(out)
    d.line(points, fill=(*core[:3],90), width=widths[1], joint="curve")
    d.line(points, fill=(*core[:3],210), width=widths[2], joint="curve")
    d.line(points, fill=(255,255,255,225), width=widths[3], joint="curve")
    return out

def save_wind():
    im=Image.new("RGBA",(W,H),(0,0,0,0))
    for j,(off,a) in enumerate([(-26,16),(-12,20),(0,24),(14,19),(27,14)]):
        pts=curve_points(off,a,j*0.55)
        layer=glow_line(pts,(83,238,225,255),(54,255,238,95),(26,12,4,2))
        im=Image.alpha_composite(im,layer)
    d=ImageDraw.Draw(im)
    cx,cy=135,122
    for arm in range(7):
        pts=[]
        for k in range(34):
            t=k/33
            th=arm*2*math.pi/7 + t*7.5
            rr=48*(1-t)+8
            pts.append((cx+math.cos(th)*rr,cy+math.sin(th)*rr*.62))
        d.line(pts, fill=(201,255,250,150), width=2)
    for _ in range(70):
        x=random.randint(30,W-25); y=int(H/2+random.gauss(0,38))
        r=random.choice([1,1,2]); d.ellipse((x-r,y-r,x+r,y+r),fill=(158,255,245,random.randint(80,190)))
    im.save(RES/"elemental_wind_132.png",optimize=True)

def save_fire():
    pts=curve_points(0,21,.2)
    im=glow_line(pts,(255,92,8,255),(255,62,0,130),(62,31,13,4))
    d=ImageDraw.Draw(im)
    for i in range(34):
        t=(i+.4)/34; x=18+t*(W-36); y=H*.5+math.sin(t*math.pi*1.15+.2)*21
        side=-1 if i%2==0 else 1
        h=18+(i%6)*6
        poly=[(x-7,y),(x+5,y),(x+random.randint(-6,8),y+side*h),(x+13,y+side*7)]
        d.polygon(poly,fill=(255,80+min(i%5*22,100),6,180))
        if i%3==0: d.line([(x,y),(x+2,y+side*h*.78)],fill=(255,236,105,210),width=3)
    for _ in range(85):
        x=random.randint(25,W-20); base=H*.5+math.sin((x/W)*math.pi*1.15+.2)*21
        y=base+random.choice([-1,1])*random.randint(28,75)
        r=random.choice([1,1,2,3]); d.ellipse((x-r,y-r,x+r,y+r),fill=(255,145+random.randint(0,100),25,random.randint(100,230)))
    im.save(RES/"elemental_fire_132.png",optimize=True)

def save_water():
    pts=curve_points(2,23,.8)
    im=glow_line(pts,(18,139,255,255),(16,172,255,120),(58,30,14,4))
    d=ImageDraw.Draw(im)
    for off in (-13,-6,8,15):
        p=curve_points(off,20+abs(off)*.25,.8+off*.03)
        d.line(p,fill=(91,218,255,130 if abs(off)>10 else 175),width=3)
    crest=curve_points(-9,22,.8)
    d.line(crest,fill=(222,252,255,220),width=4)
    for _ in range(95):
        x=random.randint(25,W-20); t=x/W; base=H*.5+math.sin(t*math.pi*1.15+.8)*23
        y=base+random.choice([-1,1])*random.randint(25,78)
        r=random.choice([1,2,2,3]); d.ellipse((x-r,y-r,x+r,y+r),outline=(149,235,255,210),width=1)
    im.save(RES/"elemental_water_132.png",optimize=True)

def save_earth():
    pts=curve_points(0,15,1.4)
    im=glow_line(pts,(131,83,43,255),(166,104,48,80),(54,32,15,3))
    d=ImageDraw.Draw(im)
    for i in range(43):
        t=(i+.25)/43; x=18+t*(W-36); y=H*.5+math.sin(t*math.pi*1.15+1.4)*15
        side=-1 if i%3==0 else (1 if i%3==1 else random.choice([-1,1]))
        y+=side*random.randint(8,42); r=random.randint(5,15)
        ptsr=[]
        n=random.randint(5,7)
        for k in range(n):
            th=2*math.pi*k/n; rr=r*random.uniform(.65,1.15)
            ptsr.append((x+math.cos(th)*rr,y+math.sin(th)*rr))
        fill=random.choice([(91,58,39,235),(126,79,44,245),(166,111,61,235),(194,139,79,225)])
        d.polygon(ptsr,fill=fill)
        d.line(ptsr+[ptsr[0]],fill=(229,179,112,115),width=1)
    for _ in range(65):
        x=random.randint(22,W-22); y=int(H*.5+random.gauss(0,42)); r=random.choice([1,2,3]); d.ellipse((x-r,y-r,x+r,y+r),fill=(176,121,69,random.randint(70,160)))
    im.save(RES/"elemental_earth_132.png",optimize=True)

def save_lightning():
    im=Image.new("RGBA",(W,H),(0,0,0,0))
    pts=[]
    for i in range(48):
        t=i/47; x=18+t*(W-36); base=H*.5+math.sin(t*math.pi*1.1+2.0)*13
        y=base+random.randint(-18,18)
        pts.append((x,y))
    halo=Image.new("RGBA",(W,H),(0,0,0,0)); hd=ImageDraw.Draw(halo)
    hd.line(pts,fill=(255,224,0,180),width=18)
    halo=halo.filter(ImageFilter.GaussianBlur(14)); im=Image.alpha_composite(im,halo)
    d=ImageDraw.Draw(im); d.line(pts,fill=(255,211,0,245),width=7); d.line(pts,fill=(255,255,194,255),width=2)
    for i in range(4,len(pts)-3,4):
        x,y=pts[i]; side=-1 if i%8==0 else 1; length=random.randint(24,62)
        branch=[(x,y),(x+10,y+side*16),(x+25,y+side*length*.65),(x+43,y+side*length)]
        d.line(branch,fill=(255,235,73,205),width=3); d.line(branch,fill=(255,255,207,220),width=1)
    im.save(RES/"elemental_lightning_132.png",optimize=True)

for fn in (save_wind,save_fire,save_water,save_earth,save_lightning): fn()

main=ROOT/"app/src/main/java/com/rpgos/app/MainActivity.kt"
s=main.read_text()
if "import androidx.compose.foundation.Image" not in s:
    s=s.replace("import androidx.compose.foundation.Canvas\n","import androidx.compose.foundation.Canvas\nimport androidx.compose.foundation.Image\n")
if "import androidx.compose.ui.res.painterResource" not in s:
    s=s.replace("import androidx.compose.ui.platform.LocalDensity\n","import androidx.compose.ui.platform.LocalDensity\nimport androidx.compose.ui.res.painterResource\n")

new_fn=r'''@Composable
private fun ElementalOrbit(
    angle: Float,
    fullEffects: Boolean,
    minimal: Boolean,
    pulse: Float
) {
    // Elemental 132: raster VFX sprites move along the original fixed ellipse.
    // The orbit angle, geometry and timing are intentionally unchanged.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val radiusX = widthPx * 0.405f
        val radiusY = heightPx * 0.365f
        val zone = 72f
        val spriteWidth = when {
            fullEffects -> 164.dp
            minimal -> 132.dp
            else -> 152.dp
        }
        val spriteHeight = when {
            fullEffects -> 92.dp
            minimal -> 70.dp
            else -> 84.dp
        }
        val assetAlpha = if (minimal) 0.78f else 1.0f
        val assets = listOf(
            R.drawable.elemental_wind_132,
            R.drawable.elemental_fire_132,
            R.drawable.elemental_water_132,
            R.drawable.elemental_earth_132,
            R.drawable.elemental_lightning_132
        )

        assets.forEachIndexed { index, drawable ->
            val a = angle + zone * (index + 0.5f)
            val r = Math.toRadians(a.toDouble())
            val cx = kotlin.math.cos(r).toFloat()
            val sy = kotlin.math.sin(r).toFloat()
            val tx = cx * radiusX
            val ty = sy * radiusY
            val dx = -radiusX * sy
            val dy = radiusY * cx
            val tangent = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()

            Image(
                painter = painterResource(drawable),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(spriteWidth, spriteHeight)
                    .graphicsLayer {
                        translationX = tx
                        translationY = ty
                        rotationZ = tangent
                        scaleX = if (minimal) 0.92f else (0.94f + pulse * 0.08f)
                        scaleY = if (minimal) 0.92f else (0.94f + pulse * 0.08f)
                        alpha = assetAlpha
                    }
            )
        }
    }
}
'''
pattern=re.compile(r'@Composable\nprivate fun ElementalOrbit\(.*?\n\}\n\n@Composable\nprivate fun StatMini',re.S)
if not pattern.search(s):
    raise SystemExit("ElementalOrbit block not found")
s=pattern.sub(new_fn+"\n@Composable\nprivate fun StatMini",s,count=1)
main.write_text(s)

build=ROOT/"app/build.gradle.kts"
b=build.read_text().replace('versionCode = 131','versionCode = 132').replace('versionName = "1.2.0-alpha5-elemental131"','versionName = "1.2.0-alpha5-elemental132"')
build.write_text(b)

md=ROOT/"ELEMENTAL_132.md"
text=md.read_text()
text += "\nImplementacja: pięć plików PNG VFX w drawable-nodpi; każdy sprite jest przesuwany po oryginalnej elipsie i ustawiany stycznie do toru. Proceduralny renderer żywiołów nie jest już używany przez ElementalOrbit.\n"
md.write_text(text)
