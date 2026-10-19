#!/usr/bin/env python3
import os, pathlib, subprocess, sys
root=pathlib.Path(__file__).resolve().parents[1]
adb=os.environ.get('ADB',str(pathlib.Path.home()/'Library/Android/sdk/platform-tools/adb'))
base=[adb,'-s',os.environ.get('ANDROID_SERIAL','emulator-5554')]
for apk in ['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
 subprocess.run(base+['install','-r',str(root/apk)],check=True)
methods=['duplicateSourceIsIdempotentButEqualPaymentsRemainSeparate','crossSourceCommonOrderDoesNotDoubleCount','unpaidRefundRepaymentAndTopupAccounting','userCorrectionWinsAndCanBeUndone','itemPricesRequireEvidenceAndMixedCategoriesRemainStructured','encryptedBackupRestoresAndWrongPasswordChangesNothing','versionOneProbeDatabaseMigratesWithoutLosingRecords','localOcrNeverReturnsAnUnredactedScreenshot','stablePaymentKeepsBothEvidenceAndEnrichesOrder','creditRepaymentFeeAndPartialRefundHaveCorrectCashflow','completeItemAllocationChangesCategoryTotalsWithoutInventingDiscounts','deferredLifecycleAndCancellationDoNotDoubleCount','amountCorrectionIsAuditedAndUndoRestoresIt','twoStrongPaymentIdsForOneOrderRemainSeparate']
targets=','.join('dev.localledger.LedgerIntegrationTest#'+m for m in methods)
r=subprocess.run(base+['shell','am','instrument','-w','-e','class',targets,'dev.localledger.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,text=True)
print(r.stdout+r.stderr)
sys.exit(0 if r.returncode==0 and f'OK ({len(methods)} tests)' in r.stdout else 1)
