from pathlib import Path
p=Path('tools/phase38_final_closure_patch.py')
s=p.read_text()
old='''replace_once(p,\n'            VisibilityPurposeKinds.SCENE_VISUALIZATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.LOCATION_VISUALIZATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),\\n',\n'            VisibilityPurposeKinds.SCENE_VISUALIZATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.LOCATION_VISUALIZATION, VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),\\n')\n'''
new='''replace_once(p,\n''' + '"""' + '''        c("rpgos-view-model", "app/src/main/java/com/rpgos/app/RpgOsViewModel.kt", ProtectedConsumerCapability.PRESENTATION_AFTER_PROJECTION,\n            VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.SCENE_VISUALIZATION,\n            VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.LOCATION_VISUALIZATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),\n''' + '"""' + ''',\n''' + '"""' + '''        c("rpgos-view-model", "app/src/main/java/com/rpgos/app/RpgOsViewModel.kt", ProtectedConsumerCapability.PRESENTATION_AFTER_PROJECTION,\n            VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.SCENE_VISUALIZATION,\n            VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.LOCATION_VISUALIZATION, VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),\n''' + '"""' + ''')\n'''
if old not in s:
    raise SystemExit('expected closure inventory anchor block not found')
p.write_text(s.replace(old,new,1))
print('closure inventory anchor normalized')
