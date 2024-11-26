/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jetbrains.micropython.settings

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.service
import com.intellij.openapi.module.Module
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.MutableCollectionComboBoxModel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.dsl.builder.*
import com.intellij.util.asSafely
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import com.jetbrains.micropython.devices.MicroPythonDeviceProvider
import com.jetbrains.micropython.nova.ConnectCredentials
import com.jetbrains.micropython.nova.ConnectionParameters
import com.jetbrains.micropython.nova.SerialPortListService
import com.jetbrains.micropython.nova.messageForBrokenUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import javax.swing.JList
import javax.swing.JPanel

/**
 * @author vlan
 */
class MicroPythonSettingsPanel(private val module: Module, disposable: Disposable) : JPanel(BorderLayout()) {
    private val deviceTypeCombo = ComboBox(MicroPythonDeviceProvider.providers.toTypedArray())

    var deviceProviderUrl = ""
    private var docsHyperlink = ActionLink("") { BrowserUtil.browse(deviceProviderUrl) }

    private val parameters = module.microPythonFacet?.let { facet ->
        ConnectionParameters(
            uart = facet.configuration.uart,
            url = facet.configuration.webReplUrl,
            password = "",
            portName = facet.configuration.portName,
        )
    } ?: ConnectionParameters("")

    val connectionPanel: DialogPanel

    init {
        border = IdeBorderFactory.createEmptyBorder(UIUtil.PANEL_SMALL_INSETS)
        runWithModalProgressBlocking(module.project, "Save password") {
            parameters.password = module.project.service<ConnectCredentials>().retrievePassword(parameters.url)
        }
        val deviceContentPanel = FormBuilder.createFormBuilder().addLabeledComponent("Device type:", deviceTypeCombo)
            .addComponent(docsHyperlink).panel
        val portSelectModel = MutableCollectionComboBoxModel<String>()
        if (parameters.portName.isNotBlank()) {
            portSelectModel.add(parameters.portName)
        }

        module.project.service<SerialPortListService>().listPorts { ports ->
            withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                portSelectModel.addAll(portSelectModel.size, ports.filter { !portSelectModel.contains(it) })
                if (portSelectModel.selectedItem.asSafely<String>().isNullOrBlank() && !portSelectModel.isEmpty) {
                    portSelectModel.selectedItem = portSelectModel.items.firstOrNull()
                }
            }
        }
        connectionPanel = panel {
            group("Connection") {
                buttonsGroup {
                    row {
                        radioButton("Serial").bindSelected(parameters::uart)
                        comboBox(portSelectModel)
                            .label("Port:")
                            .bind(
                                { it.editor.item.toString() },
                                { component, text -> component.selectedItem = text },
                                parameters::portName.toMutableProperty()
                            )
                            .validationInfo { comboBox ->
                                val portName = comboBox.selectedItem.asSafely<String>()
                                if (portName.isNullOrBlank()) ValidationInfo("No port name provided").withOKEnabled()
                                else if (!portSelectModel.contains(portName)) ValidationInfo("Unknown port name").asWarning()
                                    .withOKEnabled()
                                else null
                            }
                            .applyToComponent {
                                isEditable = true
                            }

                    }
                    separator()
                    row {
                        radioButton("WebREPL").bindSelected({ !parameters.uart }, { parameters.uart = !it })
                    }
                    indent {
                        row {
                            textField().bindText(parameters::url).label("URL: ").columns(40)
                                .validationInfo { field ->
                                    val msg = messageForBrokenUrl(field.text)
                                    msg?.let { error(it).withOKEnabled() }
                                }
                        }.layout(RowLayout.LABEL_ALIGNED)
                        row {
                            passwordField().bindText(parameters::password).label("Password: ")
                                .comment("(4-9 symbols)")
                                .columns(40)
                                .validationInfo { field ->
                                    if (field.password.size !in PASSWORD_LENGTH) error("Allowed password length is $PASSWORD_LENGTH").withOKEnabled() else null
                                }
                        }.layout(RowLayout.LABEL_ALIGNED)
                    }
                }
            }.topGap(TopGap.MEDIUM)

        }.apply {
            registerValidators(disposable)
            validateAll()

        }
        add(deviceContentPanel, BorderLayout.NORTH)
        add(connectionPanel, BorderLayout.CENTER)

        deviceTypeCombo.apply {
            renderer = object : SimpleListCellRenderer<MicroPythonDeviceProvider>() {
                override fun customize(
                    list: JList<out MicroPythonDeviceProvider>,
                    value: MicroPythonDeviceProvider?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean
                ) {
                    text = value?.presentableName ?: return
                }
            }
            addActionListener {
                docsHyperlink.apply {
                    deviceProviderUrl = selectedProvider.documentationURL
                    text = "Learn more about setting up ${selectedProvider.presentableName} devices"
                    repaint()
                }
            }
        }
    }

    fun isModified(configuration: MicroPythonFacetConfiguration): Boolean {
        return deviceTypeCombo.selectedItem != configuration.deviceProvider || connectionPanel.isModified()
    }

    fun apply(configuration: MicroPythonFacetConfiguration, facet: MicroPythonFacet) {
        configuration.deviceProvider = selectedProvider
        connectionPanel.apply()
        configuration.webReplUrl = parameters.url
        configuration.uart = parameters.uart
        configuration.portName = parameters.portName
        runWithModalProgressBlocking(facet.module.project, "Save password") {
            facet.module.project.service<ConnectCredentials>()
                .savePassword(configuration.webReplUrl, parameters.password)
        }
    }

    fun reset(configuration: MicroPythonFacetConfiguration) {
        deviceTypeCombo.selectedItem = configuration.deviceProvider
        connectionPanel.reset()
    }

    private val selectedProvider: MicroPythonDeviceProvider
        get() = deviceTypeCombo.selectedItem as MicroPythonDeviceProvider
}

private val PASSWORD_LENGTH= 4..9
