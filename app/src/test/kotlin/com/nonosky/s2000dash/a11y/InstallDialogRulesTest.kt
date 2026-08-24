package com.nonosky.s2000dash.a11y

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La parte con riesgo real del auto-confirmador.
 *
 * Un fallo aqui no es un numero mal pintado: es un servicio de
 * accesibilidad tocando el boton equivocado en una ventana del sistema.
 */
class InstallDialogRulesTest {

    // --- Lo que SI se toca --------------------------------------------------

    @Test
    fun `confirma los botones de instalar en ambos idiomas`() {
        for (t in listOf("Instalar", "INSTALAR", "install", "Install",
                         "Actualizar", "Update", "Aceptar", "OK", "Continuar", "Listo")) {
            assertTrue("debio confirmar '$t'", InstallDialogRules.isConfirmButton(t))
        }
    }

    @Test
    fun `tolera espacios y acentos`() {
        assertTrue(InstallDialogRules.isConfirmButton("  Instalar  "))
        assertTrue(InstallDialogRules.isConfirmButton("Atrás").not())
        assertTrue(InstallDialogRules.isConfirmButton("Aceptar"))
    }

    // --- Lo que JAMAS se toca -----------------------------------------------

    @Test
    fun `nunca toca un boton que cancela`() {
        // "no instalar" CONTIENE "instalar" y "cancelar" CONTIENE "cancel":
        // por eso la comparacion es exacta y no por substring. Si esto se
        // rompe, el auto-confirmador cancelaria cada actualizacion.
        for (t in listOf("Cancelar", "cancel", "No instalar", "Don't install",
                         "Rechazar", "Deny", "Desinstalar", "Uninstall", "Atrás", "Back")) {
            assertFalse("JAMAS debio tocar '$t'", InstallDialogRules.isConfirmButton(t))
        }
    }

    @Test
    fun `no toca botones desconocidos`() {
        for (t in listOf("Detalles", "Más información", "Configuración",
                         "Permitir siempre", "Conceder", "", "   ", null)) {
            assertFalse("no debio tocar '$t'", InstallDialogRules.isConfirmButton(t))
        }
    }

    // --- Alcance ------------------------------------------------------------

    @Test
    fun `solo actua sobre instaladores del sistema`() {
        assertTrue(InstallDialogRules.isInstallerPackage("com.android.packageinstaller"))
        assertTrue(InstallDialogRules.isInstallerPackage("com.google.android.packageinstaller"))
        assertTrue(InstallDialogRules.isInstallerPackage("com.android.permissioncontroller"))

        assertFalse(InstallDialogRules.isInstallerPackage("com.whatsapp"))
        assertFalse(InstallDialogRules.isInstallerPackage("com.android.settings"))
        assertFalse(InstallDialogRules.isInstallerPackage("com.android.vending"))
        assertFalse(InstallDialogRules.isInstallerPackage(null))
    }

    @Test
    fun `solo confirma instalaciones de esta app`() {
        val pkg = "com.nonosky.s2000dash"
        val label = "s2000 dash"

        assertTrue(
            InstallDialogRules.mentionsOurApp(
                listOf("¿Quieres actualizar esta aplicación?", "S2000 Dash"), pkg, label
            )
        )
        assertTrue(
            InstallDialogRules.mentionsOurApp(listOf("com.nonosky.s2000dash"), pkg, label)
        )

        // Un APK ajeno que llegue al radio no se instala solo.
        assertFalse(
            InstallDialogRules.mentionsOurApp(
                listOf("¿Quieres instalar esta aplicación?", "Juego Gratis"), pkg, label
            )
        )
        assertFalse(InstallDialogRules.mentionsOurApp(emptyList(), pkg, label))
    }
}
