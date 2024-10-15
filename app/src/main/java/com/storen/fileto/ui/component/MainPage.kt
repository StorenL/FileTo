package com.storen.fileto.ui.component

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Preview(showBackground = true)
@Composable
fun NetDebugPage(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = Modifier
            .then(modifier)
            .fillMaxSize()
            .padding(3.dp, 10.dp)
    ) {
        item {
            TextDivider(text = "连接设置")
        }
        item {
            InputOptionItem("服务端", onOpen = { ip, enable ->
            })
        }
        item {
            InputOptionItem("客户端", onOpen = { ip, enable ->
            })
        }
        item {
            TextDivider(text = "接收设置")
        }
        item {
            OptionItem("服务端接收",
                onOpen = {
                })
        }
        item {
            OptionItem("客户端接收",
                onOpen = {
                })
        }
        item {
            TextDivider(text = "发送设置(服务端)")
        }
        item {
            SendArea()
        }
        item {
            TextDivider(text = "发送设置(客户端)")
        }
        item {
            SendArea()
        }
        item {
            TextDivider(text = "输出信息")
        }
        item {
            OutputArea()
        }
    }
}

@Preview(showBackground = true)
@Composable
fun InputOptionItem(
    text: String = "服务端",
    address: String = "127.0.0.1",
    onOpen: (String, Boolean) -> Unit = { _, _ -> }
) {
    var checkedValue by remember { mutableStateOf(false) }
    var inputAddress by remember { mutableStateOf(address) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(0.dp, 3.dp)
            .shadow(2.dp, RoundedCornerShape(3.dp))
            .padding(10.dp, 0.dp)
    ) {
        Text(text = text, fontSize = 20.sp)
        BasicTextField(value = inputAddress, onValueChange = {
            inputAddress = it
        }, singleLine = true, modifier = Modifier
            .weight(1f)
            .height(40.dp),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp, 4.dp)
                        .border(1.dp, Color.Gray, shape = RoundedCornerShape(2.dp))
                        .padding(10.dp, 0.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "IP:  ")
                    innerTextField()
                }
            })
        Switch(checked = checkedValue, onCheckedChange = {
            checkedValue = it
            onOpen(inputAddress, it)
        })
    }
}

@Preview(showBackground = true)
@Composable
fun OptionItem(
    text: String = "服务端",
    enable: State<Boolean> = mutableStateOf(false),
    opened: State<Boolean> = mutableStateOf(false),
    onOpen: (Boolean) -> Unit = {}
) {
    val switchEnable by remember { enable }
    val checkedValue by remember { opened }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(0.dp, 3.dp)
            .shadow(2.dp, RoundedCornerShape(3.dp))
            .padding(10.dp, 0.dp)
    ) {
        Text(text = text, fontSize = 20.sp)
        Spacer(modifier = Modifier.weight(1f))
        Switch(checked = checkedValue, enabled = switchEnable, onCheckedChange = {
            onOpen(it)
        })
    }
}

@Preview(showBackground = true)
@Composable
fun TextDivider(text: String = "基础设置") {
    Text(
        text = "------------$text------------",
        fontSize = 18.sp,
        color = Color.Gray,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center
    )
}

@Preview(showBackground = true)
@Composable
fun SendArea(
    enable: Boolean = false,
    textSend: (String) -> Unit = {},
    imageSend: () -> Unit = {},
    protoSend: () -> Unit = {},
    fileSend: () -> Unit = {},
) {
    var inputText by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(2.dp, 10.dp)
    ) {
        TextField(value = inputText, onValueChange = {
            inputText = it
        }, modifier = Modifier.fillMaxWidth(), minLines = 1, label = {
            Text(text = "请输入待发送文字")
        }, trailingIcon = {
            IconButton(
                enabled = enable,
                onClick = {
                    textSend(inputText)
                }) {
                Icon(Icons.AutoMirrored.Filled.Send, "发送")
            }
        })
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Button(
                enabled = enable, onClick = { protoSend() }) {
                Text(text = "发送Proto")
            }
            Button(
                enabled = enable, onClick = { imageSend() }) {
                Text(text = "发送图片")
            }
            Button(
                enabled = enable, onClick = { fileSend() }) {
                Text(text = "发送文件")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun OutputArea(textState: State<String> = mutableStateOf("")) {
    val outputText by remember { textState }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(5.dp, 10.dp)
    ) {
        Text(text = outputText, fontSize = 20.sp, color = Color.Black)
    }
}