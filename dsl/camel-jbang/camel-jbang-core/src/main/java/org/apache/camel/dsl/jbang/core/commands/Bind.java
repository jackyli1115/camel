/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.dsl.jbang.core.commands;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.Callable;

import org.apache.camel.github.GitHubResourceResolver;
import org.apache.camel.util.FileUtil;
import org.apache.camel.util.IOHelper;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.YamlUnicodeReader;
import org.snakeyaml.engine.v2.composer.Composer;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.parser.Parser;
import org.snakeyaml.engine.v2.parser.ParserImpl;
import org.snakeyaml.engine.v2.scanner.StreamReader;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import static org.apache.camel.dsl.yaml.common.YamlDeserializerSupport.asStringSet;
import static org.apache.camel.dsl.yaml.common.YamlDeserializerSupport.asText;
import static org.apache.camel.dsl.yaml.common.YamlDeserializerSupport.nodeAt;

@Command(name = "bind", description = "Bind Kubernetes resources, such as Kamelets, in an integration flow")
class Bind implements Callable<Integer> {

    @CommandLine.Parameters(description = "Source such as a Kamelet or Camel endpoint uri", arity = "1")
    private String source;

    @CommandLine.Parameters(description = "Sink such as a Kamelet or Camel endpoint uri", arity = "1")
    private String sink;

    @CommandLine.Parameters(description = "Name of binding file", arity = "1")
    private String file;

    //CHECKSTYLE:OFF
    @Option(names = { "-h", "--help" }, usageHelp = true, description = "Display the help and sub-commands")
    private boolean helpRequested = false;
    //CHECKSTYLE:ON

    @Override
    public Integer call() throws Exception {
        // the kamelet binding source and sink can either be a kamelet or an uri
        String in = "kamelet";
        String out = "kamelet";
        if (source.contains(":")) {
            in = "uri";
        }
        if (sink.contains(":")) {
            out = "uri";
        }

        InputStream is = Bind.class.getClassLoader().getResourceAsStream("templates/binding-" + in + "-" + out + ".yaml.tmpl");
        String context = IOHelper.loadText(is);
        IOHelper.close(is);

        String name = FileUtil.onlyName(file, false);
        context = context.replaceFirst("\\{\\{ \\.Name }}", name);
        context = context.replaceFirst("\\{\\{ \\.Source }}", source);
        context = context.replaceFirst("\\{\\{ \\.Sink }}", sink);

        if ("kamelet".equals(in)) {
            String props = kameletProperties(source);
            context = context.replaceFirst("\\{\\{ \\.SourceProperties }}", props);
        }
        if ("kamelet".equals(out)) {
            String props = kameletProperties(sink);
            context = context.replaceFirst("\\{\\{ \\.SinkProperties }}", props);
        }

        IOHelper.writeText(context, new FileOutputStream(file, false));
        return 0;
    }

    protected String kameletProperties(String kamelet) throws Exception {
        StringBuilder sb = new StringBuilder();

        String loc = new GitHubResourceResolver()
                .resolve(
                        "github:apache:camel-kamelets:main:kamelets/" + kamelet + ".kamelet.yaml")
                .getLocation();
        if (loc != null) {
            try {
                InputStream is = new URL(loc).openStream();
                LoadSettings local = LoadSettings.builder().setLabel(loc).build();
                final StreamReader reader = new StreamReader(local, new YamlUnicodeReader(is));
                final Parser parser = new ParserImpl(local, reader);
                final Composer composer = new Composer(local, parser);
                Node root = composer.getSingleNode().orElse(null);
                if (root != null) {
                    Set<String> required = asStringSet(nodeAt(root, "/spec/definition/required"));
                    if (required != null && !required.isEmpty()) {
                        sb.append("properties:\n");
                        Iterator<String> it = required.iterator();
                        while (it.hasNext()) {
                            String req = it.next();
                            String type = asText(nodeAt(root, "/spec/definition/properties/" + req + "/type"));
                            String example = asText(nodeAt(root, "/spec/definition/properties/" + req + "/example"));
                            sb.append("      ").append(req).append(": ");
                            if (example != null) {
                                if ("string".equals(type)) {
                                    sb.append("\"");
                                }
                                sb.append(example);
                                if ("string".equals(type)) {
                                    sb.append("\"");
                                }
                            } else {
                                sb.append("\"value\"");
                            }
                            if (it.hasNext()) {
                                sb.append("\n");
                            }
                        }
                    }
                }
                IOHelper.close(is);
            } catch (FileNotFoundException e) {
                System.err.println("Kamelet not found on github: " + loc);
            } catch (Exception e) {
                e.printStackTrace();
                System.err.println("Error parsing Kamelet: " + loc + " due to: " + e.getMessage());
            }
        }

        // create a dummy placeholder, so it is easier to add new properties manually
        if (sb.length() == 0) {
            sb.append("#properties:\n      #key: \"value\"");
        }

        return sb.toString();
    }

}
