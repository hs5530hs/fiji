/*
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2007 - 2015 Fiji
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package sc.fiji.util;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;

import org.scijava.util.POM;
import org.scijava.util.XML;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

public class ComponentTable {

	private static HashMap<String, POM> pomCache = new HashMap<String, POM>();

	public static void main(final String[] args) {
		final List<POM> poms = POM.getAllPOMs();
		print("{| class=\"wikitable\"");
		print("| '''Name'''");
		print("| '''Description'''");
		print("| '''Repository'''");
		print("| '''Artifact'''");
		print("| '''License'''");
		print("| '''Team'''");
		for (final POM pom : poms) {
			if (!isRelevant(pom)) continue;

			final String g = pom.getGroupId();
			final String a = pom.getArtifactId();

			final String name = pom.getProjectName();
			final String desc = pom.getProjectDescription();
			final String url = pom.getProjectURL();

			final String scmURL = pom.getSCMURL();

			print("|-");
			print("| ", link(name, url));
			print("| ", desc);
			print("| ", scmLabel(scmURL));
			print("| ", mavenLink(g, a));
			print("| ", licenseLinks(pom));
			print("| ", teamLinks(pom));
		}
		print("|}");
	}

	// -- Helper methods --

	// TODO: Convert to configurable criteria.
	// * How to filter by: core imagej component? groupId of net.imagej
	// * How to filter out third party dependencies (i.e., not Fiji-specific)
	// Go by *parent*; it should be sc.fiji:pom-fiji
	// But also sub-projects; watch out for pom-bigdataviewer and pom-trakem2
	// Ideally we would not hard-code this, but detect from pom-fiji itself
	// via the import scope
	private static boolean isRelevant(final POM pom) {
		return "net.imagej".equals(pom.getGroupId());
	}

	private static String mavenLink(final String g, final String a) {
		return "{{Maven | g=" + g + " | a=" + a + "}}";
	}

	private static String scmLabel(final String scmURL) {
		final String[] prefixes = { "http://github.com/", "https://github.com" };
		final String[] suffixes = { ".git" };
		String label = scmURL;
		for (final String prefix : prefixes) {
			if (label.startsWith(prefix)) label = label.substring(prefix.length());
		}
		for (final String suffix : suffixes) {
			if (label.endsWith(suffix)) label = label.substring(0, suffix.length());
		}
		return label;
	}

	private static String licenseLinks(final POM pom) {
		final ArrayList<Element> licenses = licenses(pom);
		final StringBuilder sb = new StringBuilder();
		for (final Element license : licenses) {
			final String name = XML.cdata(license, "name");
			if (name == null) continue;
			final String url = XML.cdata(license, "url");
			if (sb.length() > 0) sb.append(", ");
			sb.append(link(name, url));
		}
		return sb.toString();
	}

	private static String teamLinks(final POM pom) {
		final ArrayList<Element> developers = developers(pom);
		final StringBuilder sb = new StringBuilder();
		for (final Element developer : developers) {
			final String id = XML.cdata(developer, "id");
			final String name = XML.cdata(developer, "name");
			if (sb.length() > 0) sb.append(", ");
			sb.append(personLink(id, name));
//			final ArrayList<Element> roles = XML.elements(developer, "role");
//			for (final Element role : roles) {
//				print("\t\t\t", XML.cdata(role));
//			}
		}
		return sb.toString();
	}

	private static String personLink(final String id, final String name) {
		return id == null ? name : "{{Person|" + id + "}}";
	}

	private static String link(final String label, final String url) {
		if (url == null || url.isEmpty()) return label;
		final String[] prefixes = { "http://imagej.net/", "http://fiji.sc/" };
		for (final String prefix : prefixes) {
			if (url.startsWith(prefix)) {
				final String path = url.substring(prefix.length()).replace('_', ' ');
				final String page = path.isEmpty() ? "Welcome" : path;
				return "[[" + page + "|" + label + "]]";
			}
		}
		return "[" + url + " " + label + "]";
	}

	private static ArrayList<Element> developers(final POM pom) {
		return elements(pom, "//project/developers/developer");
	}

	private static ArrayList<Element> licenses(final POM pom) {
		return elements(pom, "//project/licenses/license");
	}

	private static ArrayList<Element> elements(final POM pom, final String expr) {
		if (pom == null) return new ArrayList<Element>();
		final ArrayList<Element> elements = pom.elements(expr);
		try {
			return elements.isEmpty() ? elements(parent(pom), expr) : elements;
		}
		catch (final ParserConfigurationException exc) {
			throw new RuntimeException(exc);
		}
		catch (final SAXException exc) {
			throw new RuntimeException(exc);
		}
		catch (final IOException exc) {
			throw new RuntimeException(exc);
		}
	}

	private static POM parent(final POM pom) throws ParserConfigurationException,
		SAXException, IOException
	{
		if (pom == null) return null;
		final String parentG = pom.getParentGroupId();
		final String parentA = pom.getParentArtifactId();
		final String parentV = pom.getParentVersion();
		if (parentG == null || parentA == null || parentV == null) return null;
		return fetchPOM(parentG, parentA, parentV);
	}

	private static POM fetchPOM(final String g, final String a, final String v)
		throws ParserConfigurationException, SAXException, IOException
	{
		if (g == null || a == null || v == null) return null;
		final String gav = g + ":" + a + ":" + v;
		POM pom = pomCache.get(gav);
		if (pom == null) {
			final File file =
				new File(System.getProperty("user.home") + "/.m2/repository/" +
					g.replace('.', '/') + "/" + a + "/" + v + "/" + a + "-" + v + ".pom");
			if (file.exists()) {
				// read from Maven local repository cache
				pom = new POM(file);
			}
			else {
				// read from remote ImageJ Maven repository
				final String url =
					"http://maven.imagej.net/content/groups/public/" +
						g.replace('.', '/') + "/" + a + "/" + v + "/" + a + "-" + v +
						".pom";
				pom = new POM(new URL(url));
			}
			pomCache.put(gav, pom);
		}
		return pom;
	}

	private static void print(final Object... obj) {
		for (final Object o : obj)
			System.out.print(o.toString());
		System.out.println();
	}

}
