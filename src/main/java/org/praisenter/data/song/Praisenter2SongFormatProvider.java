package org.praisenter.data.song;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;

import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.praisenter.data.DataImportResult;
import org.praisenter.data.DataReadResult;
import org.praisenter.data.ImportExportProvider;
import org.praisenter.data.InvalidImportExportFormatException;
import org.praisenter.data.PersistAdapter;
import org.praisenter.utility.MimeType;
import org.praisenter.utility.Streams;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

final class Praisenter2SongFormatProvider implements ImportExportProvider<Song> {
	private static final Logger LOGGER = LogManager.getLogger();
	
	@Override
	public boolean isSupported(Path path) {
		return this.isSupported(MimeType.get(path));
	}
	
	@Override
	public boolean isSupported(String mimeType) {
		return MimeType.XML.is(mimeType) || mimeType.toLowerCase().startsWith("text");
	}
	
	@Override
	public boolean isSupported(String name, InputStream stream) {
		if (!stream.markSupported()) {
			LOGGER.warn("Mark is not supported on the given input stream.");
		}
		
		return this.isSupported(MimeType.get(stream, name));
	}
	
	@Override
	public void exp(PersistAdapter<Song> adapter, OutputStream stream, Song data) throws IOException {
		throw new UnsupportedOperationException();
	}
	@Override
	public void exp(PersistAdapter<Song> adapter, Path path, Song data) throws IOException {
		throw new UnsupportedOperationException();
	}
	
	@Override
	public void exp(PersistAdapter<Song> adapter, ZipArchiveOutputStream stream, Song data) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public DataImportResult<Song> imp(PersistAdapter<Song> adapter, Path path) throws IOException {
		DataImportResult<Song> result = new DataImportResult<>();
		
		if (!this.isPraisenter2Song(path)) {
			return result;
		}
		
		String name = path.getFileName().toString();
		int i = name.lastIndexOf('.');
		if (i >= 0) {
			name = name.substring(0, i);
		}
		
		List<DataReadResult<Song>> results = new ArrayList<>();
		try (FileInputStream fis = new FileInputStream(path.toFile());
			BufferedInputStream bis = new BufferedInputStream(fis)) {
			results.addAll(this.parse(bis, name));
		} catch (SAXException | ParserConfigurationException ex) {
			throw new InvalidImportExportFormatException(ex);
		}
		
		for (DataReadResult<Song> drr : results) {
			if (drr == null) continue;
			Song song = drr.getData();
			if (song == null) continue;
			try {
				boolean isUpdate = adapter.upsert(song);
				if (isUpdate) {
					result.getUpdated().add(song);
				} else {
					result.getCreated().add(song);
				}
				result.getWarnings().addAll(drr.getWarnings());
			} catch (Exception ex) {
				result.getErrors().add(ex);
			}
		}
		
		return result;
	}
	
	private boolean isPraisenter2Song(Path path) {
		try (FileInputStream stream = new FileInputStream(path.toFile())) {
			XMLInputFactory f = XMLInputFactory.newInstance();
			// prevent XXE attacks
			// https://www.owasp.org/index.php/XML_External_Entity_(XXE)_Prevention_Cheat_Sheet#XMLInputFactory_.28a_StAX_parser.29
			f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
			f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
			XMLStreamReader r = f.createXMLStreamReader(stream);
			while(r.hasNext()) {
			    r.next();
			    if (r.isStartElement()) {
			    	if (r.getLocalName().equalsIgnoreCase("songs") && "2.0.0".equals(r.getAttributeValue("", "Version"))) {
			    		return true;
			    	}
			    }
			}
		} catch (Exception ex) {
			LOGGER.trace("Failed to read the path as an XML document.", ex);
		}
		return false;
	}
	
	/**
	 * Attempts to parse the given input stream into the internal song format.
	 * @param stream the input stream
	 * @param name the name
	 * @throws IOException if an IO error occurs
	 * @throws InvalidImportExportFormatException if the stream was not in the expected format
	 * @return List
	 * @throws SAXException 
	 * @throws ParserConfigurationException 
	 */
	private List<DataReadResult<Song>> parse(InputStream stream, String name) throws ParserConfigurationException, SAXException, IOException {
		byte[] content = Streams.read(stream);
		// read the bytes
		SAXParserFactory factory = SAXParserFactory.newInstance();
		// prevent XXE attacks 
		// https://www.owasp.org/index.php/XML_External_Entity_(XXE)_Prevention_Cheat_Sheet
		factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
		factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
		factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
		SAXParser parser = factory.newSAXParser();
		Praisenter2Handler handler = new Praisenter2Handler();
		parser.parse(new ByteArrayInputStream(content), handler);
		return handler.songs.stream().map(s -> new DataReadResult<Song>(s)).collect(Collectors.toList());
	}
	
	// SAX parser implementation
	
	/**
	 * SAX parse for the Praisenter 1.0.0 format.
	 * @author William Bittle
	 * @version 3.0.0
	 */
	private final class Praisenter2Handler extends DefaultHandler {
		/** The songs */
		private List<Song> songs;
		
		/** The song currently being processed */
		private Song song;
	
		/** The lyrics currently being processed */
		private Lyrics lyrics;
		
		/** The verse currently being processed */
		private Section verse;
		
		/** Buffer for tag contents */
		private StringBuilder dataBuilder;
		
		/**
		 * Default constructor.
		 */
		public Praisenter2Handler() {
			this.songs = new ArrayList<Song>();
		}
		
		/* (non-Javadoc)
		 * @see org.xml.sax.helpers.DefaultHandler#startElement(java.lang.String, java.lang.String, java.lang.String, org.xml.sax.Attributes)
		 */
		@Override
		public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
			// inspect the tag name
			if (qName.equalsIgnoreCase("Song")) {
				// when we see the <Songs> tag we create a new song
				this.song = new Song();
				this.lyrics = new Lyrics();
				this.song.setPrimaryLyrics(this.lyrics.getId());
				this.song.getLyrics().add(this.lyrics);
			} else if (qName.equalsIgnoreCase("Part")) {
				this.verse = new Section();
				String type = getType(attributes.getValue("Type"));
				int number = 1;
				try {
					number = Integer.parseInt(attributes.getValue("Index"));
				} catch (NumberFormatException e) {
					LOGGER.warn("Failed to read verse number: {}", attributes.getValue("Index"));
				}
				this.verse.setName(type, number, null);
//				try {
//					int size = Integer.parseInt(attributes.getValue("FontSize"));
//					this.verse.setFontSize(size);
//				} catch (NumberFormatException e) {
//					LOGGER.warn("Failed to read verse font size: {}", attributes.getValue("FontSize"));
//				}
			}
		}
		
		/* (non-Javadoc)
		 * @see org.xml.sax.helpers.DefaultHandler#characters(char[], int, int)
		 */
		@Override
		public void characters(char[] ch, int start, int length) throws SAXException {
			// this method can be called a number of times for the contents of a tag
			// this is done to improve performance so we need to append the text before
			// using it
			String s = new String(ch, start, length);
			if (this.dataBuilder == null) {
				this.dataBuilder = new StringBuilder();
			}
			this.dataBuilder.append(s);
		}
		
		/* (non-Javadoc)
		 * @see org.xml.sax.helpers.DefaultHandler#endElement(java.lang.String, java.lang.String, java.lang.String)
		 */
		@Override
		public void endElement(String uri, String localName, String qName) throws SAXException {
			if ("Song".equalsIgnoreCase(qName)) {
				// we are done with the song so add it to the list
				this.songs.add(this.song);
				this.song.setName(this.song.getDefaultTitle());
				this.song = null;
				this.lyrics = null;
			} else if ("Part".equalsIgnoreCase(qName)) {
				this.lyrics.getSections().add(this.verse);
				this.verse = null;
			} else if ("Title".equalsIgnoreCase(qName)) {
				// make sure the tag was not self terminating
				if (this.dataBuilder != null) {
					// set the song title
					this.lyrics.setTitle(StringEscapeUtils.unescapeXml(this.dataBuilder.toString().trim().replaceAll("\r?\n", " ")));
				}
			} else if ("Notes".equalsIgnoreCase(qName)) {
				// make sure the tag was not self terminating
				if (this.dataBuilder != null) {
					this.song.setNotes(StringEscapeUtils.unescapeXml(this.dataBuilder.toString().trim()));
				}
			} else if ("Text".equalsIgnoreCase(qName)) {
				// make sure the tag was not self terminating
				if (this.dataBuilder != null) {
					String data = StringEscapeUtils.unescapeXml(this.dataBuilder.toString().trim());
					
					// set the text
					verse.setText(data);
				}
			}
			
			this.dataBuilder = null;
		}
		
		/**
		 * Returns the type string for the given type.
		 * @param type the type
		 * @return String
		 */
		private final String getType(String type) {
			if ("VERSE".equals(type)) {
				return "v";
			} else if ("PRECHORUS".equals(type)) {
				return "p";
			} else if ("CHORUS".equals(type)) {
				return "c";
			} else if ("BRIDGE".equals(type)) {
				return "b";
			} else if ("TAG".equals(type)) {
				return "t";
			} else if ("VAMP".equals(type)) {
				return "e";
			} else if ("END".equals(type)) {
				return "e";
			} else {
				return "o";
			}
		}
	}
}
