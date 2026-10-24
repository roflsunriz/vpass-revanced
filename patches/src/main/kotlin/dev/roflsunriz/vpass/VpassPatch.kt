package dev.roflsunriz.vpass

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val PACKAGE_NAME = "com.smbc_card.vpass"

private const val APPLICATION_CLASS = "Lcom/smbc_card/vpass/VpassApplication;"
private const val TAMPER_ACTION_CLASS = "Lcom/smbc_card/vpass/TamperAction;"
private const val BASE_ACTIVITY_CLASS = "Lcom/smbc_card/vpass/ui/BaseActivity;"
private const val SPLASH_ACTIVITY_CLASS = "Lcom/smbc_card/vpass/ui/splash/SplashActivity;"
private const val SETTINGS_GLOBAL_CLASS = "Landroid/provider/Settings\$Global;"
private const val CONTENT_RESOLVER_CLASS = "Landroid/content/ContentResolver;"
private const val STRING_CLASS = "Ljava/lang/String;"

internal data class SettingIntCheck(
    val invocationIndex: Int,
    val resultRegister: Int,
)

internal fun Instruction.asMethodReference(): MethodReference? =
    (this as? ReferenceInstruction)?.reference as? MethodReference

internal fun Instruction.asFieldReference(): FieldReference? =
    (this as? ReferenceInstruction)?.reference as? FieldReference

/**
 * adb_enabled などの Settings.Global.getInt 呼び出しのうち、
 * 直後の戻り値で分岐して起動を止める形になっているものだけを返す。
 */
internal fun findSettingIntChecks(instructions: List<Instruction>): List<SettingIntCheck> =
    instructions.mapIndexedNotNull { index, instruction ->
        val reference = instruction.asMethodReference() ?: return@mapIndexedNotNull null
        if (reference.definingClass != SETTINGS_GLOBAL_CLASS || reference.name != "getInt") {
            return@mapIndexedNotNull null
        }
        if (reference.returnType != "I") return@mapIndexedNotNull null
        if (reference.parameterTypes.map(CharSequence::toString) != listOf(CONTENT_RESOLVER_CLASS, STRING_CLASS)) {
            return@mapIndexedNotNull null
        }

        val result = instructions.getOrNull(index + 1) as? OneRegisterInstruction
            ?: return@mapIndexedNotNull null
        if (result.opcode != Opcode.MOVE_RESULT) return@mapIndexedNotNull null

        val hasBranch = instructions
            .drop(index + 2)
            .take(4)
            .filterIsInstance<OneRegisterInstruction>()
            .any { it.opcode == Opcode.IF_EQZ && it.registerA == result.registerA }
        if (!hasBranch) return@mapIndexedNotNull null

        SettingIntCheck(index, result.registerA)
    }

/** BaseActivity.onCreate から最初に呼ばれる VpassApplication の boolean 取得メソッド。 */
internal fun findApplicationBooleanCall(instructions: List<Instruction>): MethodReference? =
    instructions.asSequence()
        .mapNotNull { it.asMethodReference() }
        .firstOrNull { reference ->
            reference.definingClass == APPLICATION_CLASS &&
                reference.returnType == "Z" &&
                reference.parameterTypes.isEmpty()
        }

internal fun isTamperCallback(method: Method): Boolean =
    method.definingClass == TAMPER_ACTION_CLASS &&
        !method.name.startsWith("<")

internal fun sameField(left: FieldReference, right: FieldReference): Boolean =
    left.definingClass == right.definingClass &&
        left.name == right.name &&
        left.type == right.type

private fun BytecodePatchContext.baseActivityOnCreate(): MutableMethod =
    firstMethod {
        definingClass == BASE_ACTIVITY_CLASS &&
            name == "onCreate" &&
            parameterTypes.map(CharSequence::toString) == listOf("Landroid/os/Bundle;") &&
            returnType == "V"
    }

private fun BytecodePatchContext.applicationClass() =
    classDefs.firstOrNull { it.type == APPLICATION_CLASS }
        ?: throw PatchException("VpassApplication class was not found")

private fun BytecodePatchContext.tamperActionClass() =
    classDefs.firstOrNull { it.type == TAMPER_ACTION_CLASS }
        ?: throw PatchException("TamperAction class was not found")

private fun MutableMethod.instructions(): List<Instruction> =
    implementation?.instructions?.toList().orEmpty()

private fun MutableMethod.replaceBody(smali: String) {
    val original = implementation
        ?: throw PatchException("Method ${definingClass}->${name} has no implementation")
    // 元のtry/catch範囲は命令削除後に無効になり、getTryBlocks()は変更できない。
    // 実装ごと差し替えて、例外表・debug itemを残さない。
    implementation = MutableMethodImplementation(original.registerCount)
    addInstructions(0, smali)
}

private fun BytecodePatchContext.disableTamperCallbacks() {
    val callbacks = tamperActionClass().methods.filter(::isTamperCallback)
    if (callbacks.isEmpty()) {
        throw PatchException("TamperAction callbacks were not found")
    }
    callbacks.forEach { callback -> firstMethod(callback).replaceBody("return-void") }
}

private fun BytecodePatchContext.disableTamperFlag() {
    val onCreate = baseActivityOnCreate()
    val getterReference = findApplicationBooleanCall(onCreate.instructions())
        ?: throw PatchException("VpassApplication tamper flag getter was not found")

    val application = applicationClass()
    val getter = application.methods.firstOrNull { method ->
        method.name == getterReference.name &&
            method.definingClass == getterReference.definingClass &&
            method.parameterTypes.isEmpty() &&
            method.returnType == "Z"
    } ?: throw PatchException("VpassApplication tamper flag getter ${getterReference.name} was not found")

    val flagField = getter.implementation?.instructions?.toList().orEmpty()
        .firstOrNull { it.opcode == Opcode.IGET_BOOLEAN }
        ?.asFieldReference()
        ?: throw PatchException("VpassApplication tamper flag field was not found")

    firstMethod(getter).replaceBody(
        """
            const/4 v0, 0x0
            return v0
        """.trimIndent(),
    )

    val setters = allMethodsReferencing(flagField, setOf(Opcode.IPUT_BOOLEAN, Opcode.SPUT_BOOLEAN))
        .filterNot { method -> method.name.startsWith("<") }
    val otherReaders = allMethodsReferencing(flagField, setOf(Opcode.IGET_BOOLEAN))
        .filterNot { method ->
            method.definingClass == getter.definingClass &&
                method.name == getter.name &&
                method.returnType == getter.returnType &&
                method.parameterTypes.isEmpty()
        }
    if (otherReaders.isNotEmpty()) {
        val locations = otherReaders.joinToString { "${it.definingClass}->${it.name}" }
        throw PatchException("VpassApplication tamper flag is also read directly: $locations")
    }
    setters.forEach { setter -> firstMethod(setter).replaceBody("return-void") }
}

private fun BytecodePatchContext.allMethodsReferencing(
    field: FieldReference,
    opcodes: Set<Opcode>,
): List<Method> =
    classDefs.asSequence()
        .flatMap { it.methods.asSequence() }
        .filter { method ->
            method.implementation?.instructions?.any { instruction ->
                instruction.opcode in opcodes &&
                    instruction.asFieldReference()?.let { sameField(it, field) } == true
            } == true
        }
        .toList()

private fun BytecodePatchContext.disableSignatureCheck() {
    val splashOnCreate = firstMethod {
        definingClass == SPLASH_ACTIVITY_CLASS &&
            name == "onCreate" &&
            parameterTypes.map(CharSequence::toString) == listOf("Landroid/os/Bundle;") &&
            returnType == "V"
    }

    // DexGuardの起動時初期化は kh パッケージの static void メソッドを2つ呼ぶ。
    // そのうち、同じクラス内の (Object)Z を呼ぶものが署名検証。
    val initializers = splashOnCreate.instructions()
        .mapNotNull { it.asMethodReference() }
        .filter { reference ->
            reference.definingClass.startsWith("Lkh/") &&
                reference.parameterTypes.isEmpty() &&
                reference.returnType == "V"
        }

    val signatureInitializers = initializers.filter { initializer ->
        val classDef = classDefs.firstOrNull { it.type == initializer.definingClass }
            ?: return@filter false
        val body = classDef.methods.firstOrNull { method ->
            method.name == initializer.name &&
                method.parameterTypes.isEmpty() &&
                method.returnType == "V"
        }?.implementation?.instructions?.toList().orEmpty()
        body.mapNotNull { it.asMethodReference() }.any { reference ->
            reference.definingClass == initializer.definingClass &&
                reference.parameterTypes.size == 1 &&
                reference.returnType == "Z"
        }
    }.distinct()

    if (signatureInitializers.isEmpty()) {
        throw PatchException("DexGuard signature validation initializer was not found")
    }

    // 署名検証の初期化メソッド自体を無効化する。検証結果のbooleanだけを
    // 差し替えても、初期化中のネイティブ処理が署名を鍵に使う場合がある。
    signatureInitializers.forEach { initializer ->
        val classDef = classDefs.firstOrNull { it.type == initializer.definingClass }
            ?: throw PatchException("Signature initializer class ${initializer.definingClass} was not found")
        val method = classDef.methods.firstOrNull { candidate ->
            candidate.name == initializer.name &&
                candidate.parameterTypes.isEmpty() &&
                candidate.returnType == "V"
        } ?: throw PatchException("Signature initializer ${initializer.name} was not found")
        firstMethod(method).replaceBody("return-void")
    }
}

private fun BytecodePatchContext.disableAdbSettingChecks() {
    val onCreate = baseActivityOnCreate()
    val checks = findSettingIntChecks(onCreate.instructions())
    if (checks.isEmpty()) {
        throw PatchException("BaseActivity adb_enabled check was not found")
    }

    checks.sortedByDescending(SettingIntCheck::invocationIndex).forEach { check ->
        if (check.resultRegister < 0 || check.resultRegister > 0xFF) {
            throw PatchException("Unexpected result register v${check.resultRegister}")
        }
        onCreate.addInstructions(
            check.invocationIndex + 2,
            "const/16 v${check.resultRegister}, 0x0",
        )
    }
}

@Suppress("unused")
val vpassTamperDetectionPatch = bytecodePatch(
    name = "改ざん・Root検知の無効化",
    description = "DexGuardの改ざん検知コールバック（Root、署名、チェックサム、デバッガ、エミュレータ、フック、" +
        "動的計測、リソース改変）を無効化します。",
) {
    compatibleWith(PACKAGE_NAME)

    apply {
        disableSignatureCheck()
        disableTamperCallbacks()
        disableTamperFlag()
    }
}

@Suppress("unused")
val vpassUsbDebugDetectionPatch = bytecodePatch(
    name = "USBデバッグ検知の無効化",
    description = "BaseActivityの起動時にadb_enabledを0として扱い、USBデバッグやADB有効時のブロック画面を" +
        "表示させません。",
) {
    compatibleWith(PACKAGE_NAME)

    apply {
        disableAdbSettingChecks()
    }
}

@Suppress("unused")
val vpassReVancedPatch = bytecodePatch(
    name = "Vpass ReVanced",
    description = "Root・改ざん・デバイス整合性・USBデバッグの検知を無効化し、通常の端末としてVpassアプリを" +
        "利用できるようにします。",
) {
    compatibleWith(PACKAGE_NAME)
    dependsOn(vpassTamperDetectionPatch, vpassUsbDebugDetectionPatch)
}
