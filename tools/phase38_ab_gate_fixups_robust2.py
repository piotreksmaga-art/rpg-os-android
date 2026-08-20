from pathlib import Path

src_path = Path(__file__).with_name('phase38_ab_gate_fixups.py')
src = src_path.read_text(encoding='utf-8')
old = """if old_guard not in text: raise SystemExit('post-hardening guard anchor missing')
text=text.replace(old_guard,new_guard,1)
p(rel).write_text(text,encoding='utf-8')
"""
new = """guard_start=text.find('    private val forbiddenDirectSymbols =')
if guard_start < 0:
    guard_start=text.find('    fun looksProtected(')
if guard_start < 0: raise SystemExit('post-hardening guard/looksProtected start missing')
guard_end=text.find('\\n    private val byPath =',guard_start)
if guard_end < 0: raise SystemExit('post-hardening guard end missing')
text=text[:guard_start]+new_guard+text[guard_end:]
p(rel).write_text(text,encoding='utf-8')
"""
if old not in src:
    raise SystemExit('robust2-wrapper source anchor missing')
patched = src.replace(old, new, 1)
exec(compile(patched, str(src_path), 'exec'), {'__name__': '__main__', '__file__': str(src_path)})
