#!/bin/bash
sed -i 's/Text("List Tools")/Text("List Tools")\n                    }\n                    Button(onClick = { harnessService?.clearLog() }, modifier = Modifier.padding(end = 4.dp)) {\n                        Text("Clear")/g' app/src/main/java/com/ai/harnessdroid/ui/MainActivity.kt

