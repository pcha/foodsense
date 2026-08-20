package dev.pcha.foodsense.app.ui

import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Host vacío con inyección de Hilt, para tests de Compose que necesitan `hiltViewModel()`.
 *
 * Vive en `src/debug` porque la instrumentación sólo puede lanzar activities declaradas en la app
 * bajo prueba, y así no llega a release. Lanzar `MainActivity` en su lugar no sirve: su `setContent`
 * corre dentro de la activity y el árbol de Compose no queda registrado en el test rule.
 */
@AndroidEntryPoint
class HiltTestActivity : ComponentActivity()
