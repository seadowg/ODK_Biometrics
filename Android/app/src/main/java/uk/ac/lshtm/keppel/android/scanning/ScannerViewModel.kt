package uk.ac.lshtm.keppel.android.scanning

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import uk.ac.lshtm.keppel.android.scanning.ScannerViewModel.ScannerState.Connected
import uk.ac.lshtm.keppel.android.scanning.ScannerViewModel.ScannerState.Disconnected
import uk.ac.lshtm.keppel.android.scanning.ScannerViewModel.ScannerState.Scanning
import uk.ac.lshtm.keppel.core.CaptureResult
import uk.ac.lshtm.keppel.core.Matcher
import uk.ac.lshtm.keppel.core.Scanner
import uk.ac.lshtm.keppel.core.TaskRunner
import uk.ac.lshtm.keppel.core.fromHex

class ScannerViewModel(
    private val scanner: Scanner,
    private val matcher: Matcher,
    private val taskRunner: TaskRunner,
    private val inputTemplates: List<String> = emptyList(),
    private val fast: Boolean = false
) : ViewModel() {

    private val _scannerState = MutableLiveData<ScannerState>(Disconnected)
    private val _result = MutableLiveData<Result?>(null)

    val scannerState: LiveData<ScannerState> = _scannerState
    val result: LiveData<Result?> = _result

    init {
        scanner.connect { success ->
            if (success) {
                _scannerState.value = Connected

                if (fast) {
                    capture()
                }
            } else {
                _scannerState.value = ScannerState.ConnectionFailure
            }
        }

        scanner.onDisconnect {
            _scannerState.value = Disconnected
        }
    }

    fun capture() {
        _scannerState.value = Scanning

        taskRunner.execute {
            val capture = scanner.capture()
            if (inputTemplates.isNotEmpty() && capture != null) {
                val scores = inputTemplates.fold(emptyList<Double>()) { scores, template ->
                    val decodedInputTemplate = template.fromHex()
                    if (decodedInputTemplate != null) {
                        val score = matcher.match(
                            decodedInputTemplate,
                            capture.isoTemplate.fromHex()!!
                        )

                        if (score != null) {
                            scores + score
                        } else {
                            scores
                        }
                    } else {
                        scores
                    }
                }

                if (scores.isNotEmpty()) {
                    _result.postValue(Result.Match(scores.max(), capture))
                } else {
                    _result.postValue(Result.InputError)
                }
            } else if (capture != null) {
                _result.postValue(Result.Scan(capture))
            } else {
                _result.postValue(Result.NoCaptureResultError)
            }

            _scannerState.postValue(Connected)
        }
    }

    fun stopCapture() {
        scanner.stopCapture()
    }

    public override fun onCleared() {
        scanner.stopCapture()
        scanner.disconnect()
    }

    sealed class Result {
        data class Scan(val captureResult: CaptureResult) : Result()
        data class Match(val score: Double, val captureResult: CaptureResult) : Result()
        object InputError : Result()
        object NoCaptureResultError : Result()
    }

    sealed class ScannerState {
        object Disconnected : ScannerState()
        object Connected : ScannerState()
        object Scanning : ScannerState()
        object ConnectionFailure : ScannerState()
    }
}
