from pathlib import Path

# Temporary forward-only test-only correction; removed before final candidate.
p = Path('app/src/test/java/com/rpgos/app/Phase19FinalIntegrityHardeningTest.kt')
s = p.read_text()
old = '''            val replace = pool.submit { RpgPackageManager(app).validatedImportWorldPack(zip, "A.worldpack") }
'''
new = '''            val replace = pool.submit<Boolean> {
                RpgPackageManager(app).validatedImportWorldPack(zip, "A.worldpack").ok
            }
'''
assert s.count(old) == 1, s.count(old)
s = s.replace(old, new, 1)
old = '''            assertTrue(replace.get(10, TimeUnit.SECONDS).ok)
'''
new = '''            assertTrue(replace.get(10, TimeUnit.SECONDS))
'''
assert s.count(old) == 1, s.count(old)
s = s.replace(old, new, 1)
p.write_text(s)
# trigger v2
