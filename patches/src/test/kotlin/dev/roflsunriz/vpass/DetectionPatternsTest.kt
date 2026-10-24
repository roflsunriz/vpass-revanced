package dev.roflsunriz.vpass

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.ReferenceType
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.Reference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DetectionPatternsTest {

    @Test
    fun `finds adb_enabled check guarded by if-eqz`() {
        val instructions = listOf(
            FakeReferenceInstruction(Opcode.INVOKE_STATIC, settingsGetInt()),
            FakeRegisterInstruction(Opcode.MOVE_RESULT, register = 3),
            FakeInstruction(Opcode.IPUT),
            FakeRegisterInstruction(Opcode.IF_EQZ, register = 3),
            FakeInstruction(Opcode.RETURN_VOID),
        )

        assertEquals(listOf(SettingIntCheck(0, 3)), findSettingIntChecks(instructions))
    }

    @Test
    fun `ignores getInt result that is not branched on`() {
        val instructions = listOf(
            FakeReferenceInstruction(Opcode.INVOKE_STATIC, settingsGetInt()),
            FakeRegisterInstruction(Opcode.MOVE_RESULT, register = 0),
            FakeInstruction(Opcode.RETURN_VOID),
        )

        assertTrue(findSettingIntChecks(instructions).isEmpty())
    }

    @Test
    fun `ignores unrelated Settings getInt overload`() {
        val unrelated = FakeMethodReference(
            definingClassValue = "Landroid/provider/Settings\$Secure;",
            nameValue = "getInt",
            parameterTypesValue = listOf("Landroid/content/ContentResolver;", "Ljava/lang/String;"),
            returnTypeValue = "I",
        )
        val instructions = listOf(
            FakeReferenceInstruction(Opcode.INVOKE_STATIC, unrelated),
            FakeRegisterInstruction(Opcode.MOVE_RESULT, register = 0),
            FakeInstruction(Opcode.IPUT),
            FakeRegisterInstruction(Opcode.IF_EQZ, register = 0),
        )

        assertTrue(findSettingIntChecks(instructions).isEmpty())
    }

    @Test
    fun `finds first VpassApplication boolean getter call`() {
        val unrelated = FakeMethodReference(
            definingClassValue = "Ljava/lang/String;",
            nameValue = "isEmpty",
            parameterTypesValue = emptyList(),
            returnTypeValue = "Z",
        )
        val getter = FakeMethodReference(
            definingClassValue = "Lcom/smbc_card/vpass/VpassApplication;",
            nameValue = "m3009",
            parameterTypesValue = emptyList(),
            returnTypeValue = "Z",
        )
        val instructions = listOf(
            FakeReferenceInstruction(Opcode.INVOKE_VIRTUAL, unrelated),
            FakeReferenceInstruction(Opcode.INVOKE_VIRTUAL, getter),
        )

        assertEquals(getter, findApplicationBooleanCall(instructions))
    }

    @Test
    fun `returns null when VpassApplication boolean getter is absent`() {
        val instructions = listOf(
            FakeReferenceInstruction(
                Opcode.INVOKE_VIRTUAL,
                FakeMethodReference(
                    definingClassValue = "Lcom/smbc_card/vpass/VpassApplication;",
                    nameValue = "m3004",
                    parameterTypesValue = emptyList(),
                    returnTypeValue = "Ljava/lang/String;",
                ),
            ),
        )

        assertNull(findApplicationBooleanCall(instructions))
    }
}

private fun settingsGetInt() = FakeMethodReference(
    definingClassValue = "Landroid/provider/Settings\$Global;",
    nameValue = "getInt",
    parameterTypesValue = listOf("Landroid/content/ContentResolver;", "Ljava/lang/String;"),
    returnTypeValue = "I",
)

private class FakeMethodReference(
    private val definingClassValue: String,
    private val nameValue: String,
    private val parameterTypesValue: List<CharSequence>,
    private val returnTypeValue: String,
) : MethodReference {
    override fun getDefiningClass(): String = definingClassValue

    override fun getName(): String = nameValue

    override fun getParameterTypes(): List<CharSequence> = parameterTypesValue

    override fun getReturnType(): String = returnTypeValue

    override fun compareTo(other: MethodReference): Int = toString().compareTo(other.toString())

    override fun hashCode(): Int = toString().hashCode()

    override fun equals(other: Any?): Boolean = other is MethodReference && toString() == other.toString()

    override fun validateReference() = Unit

    override fun toString(): String =
        "$definingClassValue->$nameValue(${parameterTypesValue.joinToString("")})$returnTypeValue"
}

private class FakeReferenceInstruction(
    private val opcodeValue: Opcode,
    private val target: Reference,
) : ReferenceInstruction {
    override fun getOpcode(): Opcode = opcodeValue

    override fun getReference(): Reference = target

    override fun getReferenceType(): Int = ReferenceType.METHOD

    override fun getCodeUnits(): Int = 2
}

private class FakeRegisterInstruction(
    private val opcodeValue: Opcode,
    private val register: Int,
) : OneRegisterInstruction {
    override fun getOpcode(): Opcode = opcodeValue

    override fun getRegisterA(): Int = register

    override fun getCodeUnits(): Int = if (opcodeValue == Opcode.MOVE_RESULT) 1 else 2
}

private class FakeInstruction(
    private val opcodeValue: Opcode,
) : Instruction {
    override fun getOpcode(): Opcode = opcodeValue

    override fun getCodeUnits(): Int = 2
}
