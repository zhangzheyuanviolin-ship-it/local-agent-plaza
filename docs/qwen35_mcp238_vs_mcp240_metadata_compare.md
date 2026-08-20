# Qwen3.5 original MCP238 bundle vs MCP240 repaired bundle metadata comparison

This is an inspection-only report. No runtime or model product artifact is modified here.

## Machine summary

```json
{
  "schema": "local-agent-plaza.qwen35.original-vs-mcp240-metadata.v1",
  "original_model_sha256": "d5e975f0eb5b081b2a3f5c55e65d00e5ce7e43aad10bc1d002d5df66d82e9f73",
  "mcp240_model_sha256": "119bdfb5464b9c3746b26d43211474fcfcbbe8a8c36693e3c0413b8d5b9d7d0f",
  "original_metadata_sha256": "ad383c0d104b9719842dc71bfdad7309ea8bf8fb811270de99d46c7e7cee7ca4",
  "mcp240_metadata_sha256": "31b1c8967c32aa6ab90ad87109af4dcfe2094295c27b4ad2bbfb26bd24c30bee",
  "original_model_toml_sha256": "84d11fa648059164750be2449680ceb1e75ebdd9079d9be0bbf09a2c993719fc",
  "mcp240_model_toml_sha256": "986b7f807d0caa8880d3ef87b768c825b7b48019f99d2554847271bb9be4ecbd",
  "original_stop_ids": [
    248044
  ],
  "mcp240_stop_ids": [
    248044,
    248046
  ],
  "original_executor_metadata_present": true,
  "mcp240_executor_metadata_present": true,
  "template_changed": true,
  "model_toml_changed": true
}
```

## ORIGINAL converted bundle Jinja template

```jinja
{%- set image_count = namespace(value=0) %}
{%- set video_count = namespace(value=0) %}
{%- macro render_content(content, do_vision_count, is_system_content=false) %}
    {%- if content is string %}
        {{- content }}
    {%- elif content is iterable and content is not mapping %}
        {%- for item in content %}
            {%- if 'image' in item or 'image_url' in item or item.type == 'image' %}
                {%- if is_system_content %}
                    {{- raise_exception('System message cannot contain images.') }}
                {%- endif %}
                {%- if do_vision_count %}
                    {%- set image_count.value = image_count.value + 1 %}
                {%- endif %}
                {%- if add_vision_id %}
                    {{- 'Picture ' ~ image_count.value ~ ': ' }}
                {%- endif %}
                {{- '<|vision_start|><|image_pad|><|vision_end|>' }}
            {%- elif 'video' in item or item.type == 'video' %}
                {%- if is_system_content %}
                    {{- raise_exception('System message cannot contain videos.') }}
                {%- endif %}
                {%- if do_vision_count %}
                    {%- set video_count.value = video_count.value + 1 %}
                {%- endif %}
                {%- if add_vision_id %}
                    {{- 'Video ' ~ video_count.value ~ ': ' }}
                {%- endif %}
                {{- '<|vision_start|><|video_pad|><|vision_end|>' }}
            {%- elif 'text' in item %}
                {{- item.text }}
            {%- else %}
                {{- raise_exception('Unexpected item type in content.') }}
            {%- endif %}
        {%- endfor %}
    {%- elif content is none or content is undefined %}
        {{- '' }}
    {%- else %}
        {{- raise_exception('Unexpected content type.') }}
    {%- endif %}
{%- endmacro %}
{%- if not messages %}
    {{- raise_exception('No messages provided.') }}
{%- endif %}
{%- if tools and tools is iterable and tools is not mapping %}
    {{- '<|im_start|>system\n' }}
    {{- "# Tools\n\nYou have access to the following functions:\n\n<tools>" }}
    {%- for tool in tools %}
        {{- "\n" }}
        {{- tool | tojson }}
    {%- endfor %}
    {{- "\n</tools>" }}
    {{- '\n\nIf you choose to call a function ONLY reply in the following format with NO suffix:\n\n<tool_call>\n<function=example_function_name>\n<parameter=example_parameter_1>\nvalue_1\n</parameter>\n<parameter=example_parameter_2>\nThis is the value for the second parameter\nthat can span\nmultiple lines\n</parameter>\n</function>\n</tool_call>\n\n<IMPORTANT>\nReminder:\n- Function calls MUST follow the specified format: an inner <function=...></function> block must be nested within <tool_call></tool_call> XML tags\n- Required parameters MUST be specified\n- You may provide optional reasoning for your function call in natural language BEFORE the function call, but NOT after\n- If there is no function call available, answer the question like normal with your current knowledge and do not tell the user about function calls\n</IMPORTANT>' }}
    {%- if messages[0].role == 'system' %}
        {%- set content = render_content(messages[0].content, false, true)|trim %}
        {%- if content %}
            {{- '\n\n' + content }}
        {%- endif %}
    {%- endif %}
    {{- '<|im_end|>\n' }}
{%- else %}
    {%- if messages[0].role == 'system' %}
        {%- set content = render_content(messages[0].content, false, true)|trim %}
        {{- '<|im_start|>system\n' + content + '<|im_end|>\n' }}
    {%- endif %}
{%- endif %}
{%- set ns = namespace(multi_step_tool=true, last_query_index=messages|length - 1) %}
{%- for message in messages[::-1] %}
    {%- set index = (messages|length - 1) - loop.index0 %}
    {%- if ns.multi_step_tool and message.role == "user" %}
        {%- set content = render_content(message.content, false)|trim %}
        {%- if not(content.startswith('<tool_response>') and content.endswith('</tool_response>')) %}
            {%- set ns.multi_step_tool = false %}
            {%- set ns.last_query_index = index %}
        {%- endif %}
    {%- endif %}
{%- endfor %}
{%- if ns.multi_step_tool %}
    {{- raise_exception('No user query found in messages.') }}
{%- endif %}
{%- for message in messages %}
    {%- set content = render_content(message.content, true)|trim %}
    {%- if message.role == "system" %}
        {%- if not loop.first %}
            {{- raise_exception('System message must be at the beginning.') }}
        {%- endif %}
    {%- elif message.role == "user" %}
        {{- '<|im_start|>' + message.role + '\n' + content + '<|im_end|>' + '\n' }}
    {%- elif message.role == "assistant" %}
        {%- set reasoning_content = '' %}
        {%- if message.reasoning_content is string %}
            {%- set reasoning_content = message.reasoning_content %}
        {%- else %}
            {%- if '</think>' in content %}
                {%- set reasoning_content = content.split('</think>')[0].rstrip('\n').split('<think>')[-1].lstrip('\n') %}
                {%- set content = content.split('</think>')[-1].lstrip('\n') %}
            {%- endif %}
        {%- endif %}
        {%- set reasoning_content = reasoning_content|trim %}
        {%- if loop.index0 > ns.last_query_index %}
            {{- '<|im_start|>' + message.role + '\n<think>\n' + reasoning_content + '\n</think>\n\n' + content }}
        {%- else %}
            {{- '<|im_start|>' + message.role + '\n' + content }}
        {%- endif %}
        {%- if message.tool_calls and message.tool_calls is iterable and message.tool_calls is not mapping %}
            {%- for tool_call in message.tool_calls %}
                {%- if tool_call.function is defined %}
                    {%- set tool_call = tool_call.function %}
                {%- endif %}
                {%- if loop.first %}
                    {%- if content|trim %}
                        {{- '\n\n<tool_call>\n<function=' + tool_call.name + '>\n' }}
                    {%- else %}
                        {{- '<tool_call>\n<function=' + tool_call.name + '>\n' }}
                    {%- endif %}
                {%- else %}
                    {{- '\n<tool_call>\n<function=' + tool_call.name + '>\n' }}
                {%- endif %}
                {%- if tool_call.arguments is defined %}
                    {%- for args_name, args_value in tool_call.arguments|items %}
                        {{- '<parameter=' + args_name + '>\n' }}
                        {%- set args_value = args_value | tojson | safe if args_value is mapping or (args_value is sequence and args_value is not string) else args_value | string %}
                        {{- args_value }}
                        {{- '\n</parameter>\n' }}
                    {%- endfor %}
                {%- endif %}
                {{- '</function>\n</tool_call>' }}
            {%- endfor %}
        {%- endif %}
        {{- '<|im_end|>\n' }}
    {%- elif message.role == "tool" %}
        {%- if loop.previtem and loop.previtem.role != "tool" %}
            {{- '<|im_start|>user' }}
        {%- endif %}
        {{- '\n<tool_response>\n' }}
        {{- content }}
        {{- '\n</tool_response>' }}
        {%- if not loop.last and loop.nextitem.role != "tool" %}
            {{- '<|im_end|>\n' }}
        {%- elif loop.last %}
            {{- '<|im_end|>\n' }}
        {%- endif %}
    {%- else %}
        {{- raise_exception('Unexpected message role.') }}
    {%- endif %}
{%- endfor %}
{%- if add_generation_prompt %}
    {{- '<|im_start|>assistant\n' }}
    {%- if enable_thinking is defined and enable_thinking is true %}
        {{- '<think>\n' }}
    {%- else %}
        {{- '<think>\n\n</think>\n\n' }}
    {%- endif %}
{%- endif %}
```

## MCP240 repaired bundle Jinja template

```jinja
{%- for message in messages -%}
{%- if message.role == 'assistant' -%}
<|im_start|>assistant
<think>

</think>

{{ message.content }}<|im_end|>
{% else -%}
<|im_start|>{{ message.role }}
{{ message.content }}<|im_end|>
{% endif -%}
{%- endfor -%}
{%- if add_generation_prompt -%}
<|im_start|>assistant
<think>

</think>

{% endif -%}

```

## Template unified diff

```diff
--- original_template
+++ mcp240_template
@@ -1,154 +1,20 @@
-{%- set image_count = namespace(value=0) %}
-{%- set video_count = namespace(value=0) %}
-{%- macro render_content(content, do_vision_count, is_system_content=false) %}
-    {%- if content is string %}
-        {{- content }}
-    {%- elif content is iterable and content is not mapping %}
-        {%- for item in content %}
-            {%- if 'image' in item or 'image_url' in item or item.type == 'image' %}
-                {%- if is_system_content %}
-                    {{- raise_exception('System message cannot contain images.') }}
-                {%- endif %}
-                {%- if do_vision_count %}
-                    {%- set image_count.value = image_count.value + 1 %}
-                {%- endif %}
-                {%- if add_vision_id %}
-                    {{- 'Picture ' ~ image_count.value ~ ': ' }}
-                {%- endif %}
-                {{- '<|vision_start|><|image_pad|><|vision_end|>' }}
-            {%- elif 'video' in item or item.type == 'video' %}
-                {%- if is_system_content %}
-                    {{- raise_exception('System message cannot contain videos.') }}
-                {%- endif %}
-                {%- if do_vision_count %}
-                    {%- set video_count.value = video_count.value + 1 %}
-                {%- endif %}
-                {%- if add_vision_id %}
-                    {{- 'Video ' ~ video_count.value ~ ': ' }}
-                {%- endif %}
-                {{- '<|vision_start|><|video_pad|><|vision_end|>' }}
-            {%- elif 'text' in item %}
-                {{- item.text }}
-            {%- else %}
-                {{- raise_exception('Unexpected item type in content.') }}
-            {%- endif %}
-        {%- endfor %}
-    {%- elif content is none or content is undefined %}
-        {{- '' }}
-    {%- else %}
-        {{- raise_exception('Unexpected content type.') }}
-    {%- endif %}
-{%- endmacro %}
-{%- if not messages %}
-    {{- raise_exception('No messages provided.') }}
-{%- endif %}
-{%- if tools and tools is iterable and tools is not mapping %}
-    {{- '<|im_start|>system\n' }}
-    {{- "# Tools\n\nYou have access to the following functions:\n\n<tools>" }}
-    {%- for tool in tools %}
-        {{- "\n" }}
-        {{- tool | tojson }}
-    {%- endfor %}
-    {{- "\n</tools>" }}
-    {{- '\n\nIf you choose to call a function ONLY reply in the following format with NO suffix:\n\n<tool_call>\n<function=example_function_name>\n<parameter=example_parameter_1>\nvalue_1\n</parameter>\n<parameter=example_parameter_2>\nThis is the value for the second parameter\nthat can span\nmultiple lines\n</parameter>\n</function>\n</tool_call>\n\n<IMPORTANT>\nReminder:\n- Function calls MUST follow the specified format: an inner <function=...></function> block must be nested within <tool_call></tool_call> XML tags\n- Required parameters MUST be specified\n- You may provide optional reasoning for your function call in natural language BEFORE the function call, but NOT after\n- If there is no function call available, answer the question like normal with your current knowledge and do not tell the user about function calls\n</IMPORTANT>' }}
-    {%- if messages[0].role == 'system' %}
-        {%- set content = render_content(messages[0].content, false, true)|trim %}
-        {%- if content %}
-            {{- '\n\n' + content }}
-        {%- endif %}
-    {%- endif %}
-    {{- '<|im_end|>\n' }}
-{%- else %}
-    {%- if messages[0].role == 'system' %}
-        {%- set content = render_content(messages[0].content, false, true)|trim %}
-        {{- '<|im_start|>system\n' + content + '<|im_end|>\n' }}
-    {%- endif %}
-{%- endif %}
-{%- set ns = namespace(multi_step_tool=true, last_query_index=messages|length - 1) %}
-{%- for message in messages[::-1] %}
-    {%- set index = (messages|length - 1) - loop.index0 %}
-    {%- if ns.multi_step_tool and message.role == "user" %}
-        {%- set content = render_content(message.content, false)|trim %}
-        {%- if not(content.startswith('<tool_response>') and content.endswith('</tool_response>')) %}
-            {%- set ns.multi_step_tool = false %}
-            {%- set ns.last_query_index = index %}
-        {%- endif %}
-    {%- endif %}
-{%- endfor %}
-{%- if ns.multi_step_tool %}
-    {{- raise_exception('No user query found in messages.') }}
-{%- endif %}
-{%- for message in messages %}
-    {%- set content = render_content(message.content, true)|trim %}
-    {%- if message.role == "system" %}
-        {%- if not loop.first %}
-            {{- raise_exception('System message must be at the beginning.') }}
-        {%- endif %}
-    {%- elif message.role == "user" %}
-        {{- '<|im_start|>' + message.role + '\n' + content + '<|im_end|>' + '\n' }}
-    {%- elif message.role == "assistant" %}
-        {%- set reasoning_content = '' %}
-        {%- if message.reasoning_content is string %}
-            {%- set reasoning_content = message.reasoning_content %}
-        {%- else %}
-            {%- if '</think>' in content %}
-                {%- set reasoning_content = content.split('</think>')[0].rstrip('\n').split('<think>')[-1].lstrip('\n') %}
-                {%- set content = content.split('</think>')[-1].lstrip('\n') %}
-            {%- endif %}
-        {%- endif %}
-        {%- set reasoning_content = reasoning_content|trim %}
-        {%- if loop.index0 > ns.last_query_index %}
-            {{- '<|im_start|>' + message.role + '\n<think>\n' + reasoning_content + '\n</think>\n\n' + content }}
-        {%- else %}
-            {{- '<|im_start|>' + message.role + '\n' + content }}
-        {%- endif %}
-        {%- if message.tool_calls and message.tool_calls is iterable and message.tool_calls is not mapping %}
-            {%- for tool_call in message.tool_calls %}
-                {%- if tool_call.function is defined %}
-                    {%- set tool_call = tool_call.function %}
-                {%- endif %}
-                {%- if loop.first %}
-                    {%- if content|trim %}
-                        {{- '\n\n<tool_call>\n<function=' + tool_call.name + '>\n' }}
-                    {%- else %}
-                        {{- '<tool_call>\n<function=' + tool_call.name + '>\n' }}
-                    {%- endif %}
-                {%- else %}
-                    {{- '\n<tool_call>\n<function=' + tool_call.name + '>\n' }}
-                {%- endif %}
-                {%- if tool_call.arguments is defined %}
-                    {%- for args_name, args_value in tool_call.arguments|items %}
-                        {{- '<parameter=' + args_name + '>\n' }}
-                        {%- set args_value = args_value | tojson | safe if args_value is mapping or (args_value is sequence and args_value is not string) else args_value | string %}
-                        {{- args_value }}
-                        {{- '\n</parameter>\n' }}
-                    {%- endfor %}
-                {%- endif %}
-                {{- '</function>\n</tool_call>' }}
-            {%- endfor %}
-        {%- endif %}
-        {{- '<|im_end|>\n' }}
-    {%- elif message.role == "tool" %}
-        {%- if loop.previtem and loop.previtem.role != "tool" %}
-            {{- '<|im_start|>user' }}
-        {%- endif %}
-        {{- '\n<tool_response>\n' }}
-        {{- content }}
-        {{- '\n</tool_response>' }}
-        {%- if not loop.last and loop.nextitem.role != "tool" %}
-            {{- '<|im_end|>\n' }}
-        {%- elif loop.last %}
-            {{- '<|im_end|>\n' }}
-        {%- endif %}
-    {%- else %}
-        {{- raise_exception('Unexpected message role.') }}
-    {%- endif %}
-{%- endfor %}
-{%- if add_generation_prompt %}
-    {{- '<|im_start|>assistant\n' }}
-    {%- if enable_thinking is defined and enable_thinking is true %}
-        {{- '<think>\n' }}
-    {%- else %}
-        {{- '<think>\n\n</think>\n\n' }}
-    {%- endif %}
-{%- endif %}+{%- for message in messages -%}
+{%- if message.role == 'assistant' -%}
+<|im_start|>assistant
+<think>
+
+</think>
+
+{{ message.content }}<|im_end|>
+{% else -%}
+<|im_start|>{{ message.role }}
+{{ message.content }}<|im_end|>
+{% endif -%}
+{%- endfor -%}
+{%- if add_generation_prompt -%}
+<|im_start|>assistant
+<think>
+
+</think>
+
+{% endif -%}

```

## Full LlmMetadataProto unified diff

```diff
--- original_LlmMetadataProto.pbtext
+++ mcp240_LlmMetadataProto.pbtext
@@ -1,6 +1,11 @@
 stop_tokens {
   token_ids {
     ids: 248044
+  }
+}
+stop_tokens {
+  token_ids {
+    ids: 248046
   }
 }
 sampler_params {
@@ -13,7 +18,7 @@
   generic_model {
   }
 }
-jinja_prompt_template: "{%- set image_count = namespace(value=0) %}\n{%- set video_count = namespace(value=0) %}\n{%- macro render_content(content, do_vision_count, is_system_content=false) %}\n    {%- if content is string %}\n        {{- content }}\n    {%- elif content is iterable and content is not mapping %}\n        {%- for item in content %}\n            {%- if \'image\' in item or \'image_url\' in item or item.type == \'image\' %}\n                {%- if is_system_content %}\n                    {{- raise_exception(\'System message cannot contain images.\') }}\n                {%- endif %}\n                {%- if do_vision_count %}\n                    {%- set image_count.value = image_count.value + 1 %}\n                {%- endif %}\n                {%- if add_vision_id %}\n                    {{- \'Picture \' ~ image_count.value ~ \': \' }}\n                {%- endif %}\n                {{- \'<|vision_start|><|image_pad|><|vision_end|>\' }}\n            {%- elif \'video\' in item or item.type == \'video\' %}\n                {%- if is_system_content %}\n                    {{- raise_exception(\'System message cannot contain videos.\') }}\n                {%- endif %}\n                {%- if do_vision_count %}\n                    {%- set video_count.value = video_count.value + 1 %}\n                {%- endif %}\n                {%- if add_vision_id %}\n                    {{- \'Video \' ~ video_count.value ~ \': \' }}\n                {%- endif %}\n                {{- \'<|vision_start|><|video_pad|><|vision_end|>\' }}\n            {%- elif \'text\' in item %}\n                {{- item.text }}\n            {%- else %}\n                {{- raise_exception(\'Unexpected item type in content.\') }}\n            {%- endif %}\n        {%- endfor %}\n    {%- elif content is none or content is undefined %}\n        {{- \'\' }}\n    {%- else %}\n        {{- raise_exception(\'Unexpected content type.\') }}\n    {%- endif %}\n{%- endmacro %}\n{%- if not messages %}\n    {{- raise_exception(\'No messages provided.\') }}\n{%- endif %}\n{%- if tools and tools is iterable and tools is not mapping %}\n    {{- \'<|im_start|>system\\n\' }}\n    {{- \"# Tools\\n\\nYou have access to the following functions:\\n\\n<tools>\" }}\n    {%- for tool in tools %}\n        {{- \"\\n\" }}\n        {{- tool | tojson }}\n    {%- endfor %}\n    {{- \"\\n</tools>\" }}\n    {{- \'\\n\\nIf you choose to call a function ONLY reply in the following format with NO suffix:\\n\\n<tool_call>\\n<function=example_function_name>\\n<parameter=example_parameter_1>\\nvalue_1\\n</parameter>\\n<parameter=example_parameter_2>\\nThis is the value for the second parameter\\nthat can span\\nmultiple lines\\n</parameter>\\n</function>\\n</tool_call>\\n\\n<IMPORTANT>\\nReminder:\\n- Function calls MUST follow the specified format: an inner <function=...></function> block must be nested within <tool_call></tool_call> XML tags\\n- Required parameters MUST be specified\\n- You may provide optional reasoning for your function call in natural language BEFORE the function call, but NOT after\\n- If there is no function call available, answer the question like normal with your current knowledge and do not tell the user about function calls\\n</IMPORTANT>\' }}\n    {%- if messages[0].role == \'system\' %}\n        {%- set content = render_content(messages[0].content, false, true)|trim %}\n        {%- if content %}\n            {{- \'\\n\\n\' + content }}\n        {%- endif %}\n    {%- endif %}\n    {{- \'<|im_end|>\\n\' }}\n{%- else %}\n    {%- if messages[0].role == \'system\' %}\n        {%- set content = render_content(messages[0].content, false, true)|trim %}\n        {{- \'<|im_start|>system\\n\' + content + \'<|im_end|>\\n\' }}\n    {%- endif %}\n{%- endif %}\n{%- set ns = namespace(multi_step_tool=true, last_query_index=messages|length - 1) %}\n{%- for message in messages[::-1] %}\n    {%- set index = (messages|length - 1) - loop.index0 %}\n    {%- if ns.multi_step_tool and message.role == \"user\" %}\n        {%- set content = render_content(message.content, false)|trim %}\n        {%- if not(content.startswith(\'<tool_response>\') and content.endswith(\'</tool_response>\')) %}\n            {%- set ns.multi_step_tool = false %}\n            {%- set ns.last_query_index = index %}\n        {%- endif %}\n    {%- endif %}\n{%- endfor %}\n{%- if ns.multi_step_tool %}\n    {{- raise_exception(\'No user query found in messages.\') }}\n{%- endif %}\n{%- for message in messages %}\n    {%- set content = render_content(message.content, true)|trim %}\n    {%- if message.role == \"system\" %}\n        {%- if not loop.first %}\n            {{- raise_exception(\'System message must be at the beginning.\') }}\n        {%- endif %}\n    {%- elif message.role == \"user\" %}\n        {{- \'<|im_start|>\' + message.role + \'\\n\' + content + \'<|im_end|>\' + \'\\n\' }}\n    {%- elif message.role == \"assistant\" %}\n        {%- set reasoning_content = \'\' %}\n        {%- if message.reasoning_content is string %}\n            {%- set reasoning_content = message.reasoning_content %}\n        {%- else %}\n            {%- if \'</think>\' in content %}\n                {%- set reasoning_content = content.split(\'</think>\')[0].rstrip(\'\\n\').split(\'<think>\')[-1].lstrip(\'\\n\') %}\n                {%- set content = content.split(\'</think>\')[-1].lstrip(\'\\n\') %}\n            {%- endif %}\n        {%- endif %}\n        {%- set reasoning_content = reasoning_content|trim %}\n        {%- if loop.index0 > ns.last_query_index %}\n            {{- \'<|im_start|>\' + message.role + \'\\n<think>\\n\' + reasoning_content + \'\\n</think>\\n\\n\' + content }}\n        {%- else %}\n            {{- \'<|im_start|>\' + message.role + \'\\n\' + content }}\n        {%- endif %}\n        {%- if message.tool_calls and message.tool_calls is iterable and message.tool_calls is not mapping %}\n            {%- for tool_call in message.tool_calls %}\n                {%- if tool_call.function is defined %}\n                    {%- set tool_call = tool_call.function %}\n                {%- endif %}\n                {%- if loop.first %}\n                    {%- if content|trim %}\n                        {{- \'\\n\\n<tool_call>\\n<function=\' + tool_call.name + \'>\\n\' }}\n                    {%- else %}\n                        {{- \'<tool_call>\\n<function=\' + tool_call.name + \'>\\n\' }}\n                    {%- endif %}\n                {%- else %}\n                    {{- \'\\n<tool_call>\\n<function=\' + tool_call.name + \'>\\n\' }}\n                {%- endif %}\n                {%- if tool_call.arguments is defined %}\n                    {%- for args_name, args_value in tool_call.arguments|items %}\n                        {{- \'<parameter=\' + args_name + \'>\\n\' }}\n                        {%- set args_value = args_value | tojson | safe if args_value is mapping or (args_value is sequence and args_value is not string) else args_value | string %}\n                        {{- args_value }}\n                        {{- \'\\n</parameter>\\n\' }}\n                    {%- endfor %}\n                {%- endif %}\n                {{- \'</function>\\n</tool_call>\' }}\n            {%- endfor %}\n        {%- endif %}\n        {{- \'<|im_end|>\\n\' }}\n    {%- elif message.role == \"tool\" %}\n        {%- if loop.previtem and loop.previtem.role != \"tool\" %}\n            {{- \'<|im_start|>user\' }}\n        {%- endif %}\n        {{- \'\\n<tool_response>\\n\' }}\n        {{- content }}\n        {{- \'\\n</tool_response>\' }}\n        {%- if not loop.last and loop.nextitem.role != \"tool\" %}\n            {{- \'<|im_end|>\\n\' }}\n        {%- elif loop.last %}\n            {{- \'<|im_end|>\\n\' }}\n        {%- endif %}\n    {%- else %}\n        {{- raise_exception(\'Unexpected message role.\') }}\n    {%- endif %}\n{%- endfor %}\n{%- if add_generation_prompt %}\n    {{- \'<|im_start|>assistant\\n\' }}\n    {%- if enable_thinking is defined and enable_thinking is true %}\n        {{- \'<think>\\n\' }}\n    {%- else %}\n        {{- \'<think>\\n\\n</think>\\n\\n\' }}\n    {%- endif %}\n{%- endif %}"
+jinja_prompt_template: "{%- for message in messages -%}\n{%- if message.role == \'assistant\' -%}\n<|im_start|>assistant\n<think>\n\n</think>\n\n{{ message.content }}<|im_end|>\n{% else -%}\n<|im_start|>{{ message.role }}\n{{ message.content }}<|im_end|>\n{% endif -%}\n{%- endfor -%}\n{%- if add_generation_prompt -%}\n<|im_start|>assistant\n<think>\n\n</think>\n\n{% endif -%}\n"
 channels {
   channel_name: "thought"
   start: "<think>"

```

## model.toml unified diff

```diff
--- original_model.toml
+++ mcp240_model.toml
@@ -1,8 +1,8 @@
 [system_metadata]
 entries = [
   { key = "Authors", value_type = "String", value = "ODML" },
-  { key = "uuid", value_type = "String", value = "a52ac780-0aad-4232-a575-e6588c475ff9" },
-  { key = "creation_timestamp", value_type = "String", value = "2026-08-19T13:17:36.973337+00:00" },
+  { key = "uuid", value_type = "String", value = "43dba9ac-1c6b-49bb-8547-5a860b4a3619" },
+  { key = "creation_timestamp", value_type = "String", value = "2026-08-20T03:53:21.856580+00:00" },
 ]
 
 [[section]]

```
