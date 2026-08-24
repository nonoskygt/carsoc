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
                         "Actualizar", "Update", "  Instalar  ")) {
            assertTrue("debio confirmar '$t'", InstallDialogRules.isConfirmButton(t))
        }
    }

    @Test
    fun `no confirma con Aceptar ni OK`() {
        // Son los botones del dialogo de DESINSTALAR y de los de permisos.
        // Estaban en la lista de confirmacion y el auto-confirmador podia
        // acabar borrando la app o concediendo permisos sin que nadie viera.
        for (t in listOf("Aceptar", "OK", "Listo", "Continuar", "Permitir", "Conceder")) {
            assertFalse("JAMAS debio tocar '$t'", InstallDialogRules.isConfirmButton(t))
        }
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
    fun `sin sesion armada no se confirma nada`() {
        // Aunque la ventana diga ser nuestra: ese texto lo pone el APK que
        // se esta instalando, no el sistema.
        assertFalse(
            InstallDialogRules.puedeConfirmar(
                listOf("¿Quieres actualizar esta aplicación?", "S2000 Dash"),
                sesionArmada = false,
            )
        )
    }

    @Test
    fun `con sesion armada se confirma la instalacion`() {
        assertTrue(
            InstallDialogRules.puedeConfirmar(
                listOf("¿Quieres actualizar esta aplicación?", "S2000 Dash"),
                sesionArmada = true,
            )
        )
    }

    @Test
    fun `ni armado se confirma un dialogo de desinstalar o de permisos`() {
        // Si por lo que sea aparece otro dialogo dentro de la ventana de
        // tiempo, el servicio se aparta.
        assertFalse(
            InstallDialogRules.puedeConfirmar(
                listOf("¿Quieres desinstalar esta aplicación?"), sesionArmada = true
            )
        )
        assertFalse(
            InstallDialogRules.puedeConfirmar(
                listOf("¿Permitir que S2000 Dash acceda a tu ubicación?"), sesionArmada = true
            )
        )
    }
}
