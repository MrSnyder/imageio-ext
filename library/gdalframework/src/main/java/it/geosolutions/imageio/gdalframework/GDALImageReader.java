/*
 *    JImageIO-extension - OpenSource Java Image translation Library
 *    http://www.geo-solutions.it/
 *    (C) 2007, GeoSolutions
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package it.geosolutions.imageio.gdalframework;

import it.geosolutions.imageio.stream.input.FileImageInputStreamExt;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BandedSampleModel;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferFloat;
import java.awt.image.DataBufferInt;
import java.awt.image.DataBufferShort;
import java.awt.image.PixelInterleavedSampleModel;
import java.awt.image.Raster;
import java.awt.image.SampleModel;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import javax.media.jai.DataBufferDouble;
import javax.media.jai.RasterFactory;

import org.gdal.gdal.Band;
import org.gdal.gdal.Dataset;
import org.gdal.gdal.gdal;
import org.gdal.gdalconst.gdalconst;
import org.gdal.gdalconst.gdalconstConstants;

import com.sun.media.imageioimpl.common.ImageUtil;

/**
 * Main abstract class defining the main framework which need to be used to
 * extend ImageIO architecture using GDAL (Geospatial Data Abstraction Layer)
 * SWIG (Simplified Wrapper and Interface Generator) bindings in order to
 * perform read operations.
 * 
 * @author Daniele Romagnoli
 * @author Simone Giannecchini
 */
public abstract class GDALImageReader extends ImageReader {

	/** The LOGGER for this class. */
	private static final Logger LOGGER = Logger
			.getLogger("it.geosolutions.imageio.gdalframework");

	public void setInput(Object input, boolean seekForwardOnly) {
		this.setInput(input);
	}

	/** List associated to gdalImageMetadata */
	protected List gdalImageMetadata;

	/** List associated to gdalStreamMetadata */
	protected List gdalStreamMetadata;

	/** list of childs subdatasets names (if any) contained into the source */
	protected String datasetNames[];

	/** number of subdatasets */
	protected int nSubdatasets = -1;

	/** The ImageInputStream */
	protected ImageInputStream imageInputStream;

	/** The originating provider. It is used to retrieve supported formats */
	protected GDALImageReaderSpi spi;

	protected volatile boolean isInitialized = false;

	/** Principal {@link ImageTypeSpecifier} */
	protected ImageTypeSpecifier imageType = null;

	/** The dataset input source */
	protected File datasetSource = null;

	/** Hashmap containing couples (datasetName, GDALDatasetWrapper). */
	protected Map datasetMap = Collections.synchronizedMap(new HashMap(10));

	/** Inner Class used to store dataset useful information for later usage */
	public class GDALDatasetWrapper {

		// //////////////////////////////////////////////////////////////////////////////////
		// 
		// Start of GDALDatasetWrapper Class
		// 
		// //////////////////////////////////////////////////////////////////////////////////

		protected String driverName;

		protected String driverDescription;

		protected String datasetDescription;

		/** gridToWorld transformation. */
		protected double[] geoTransformation = new double[6];

		/** Data set projection. */
		protected String projection;

		/** The number of Ground Control Points */
		protected int gcpNumber;

		/** The GCP's Projection */
		protected String gcpProjection;

		/** The list of Ground Control Points */
		protected List gcps;

		/** Raster Width */
		protected int width;

		/** Raster Height */
		protected int height;

		/** Raster Tile Height */
		protected int tileHeight;

		/** Raster Tile Width */
		protected int tileWidth;

		/** Dataset Name */
		protected String datasetName;

		/** ColorModel used for this Dataset */
		protected ColorModel colorModel;

		/** SampleModel used for this Dataset */
		protected SampleModel sampleModel;

		// ////////////////////////////////////////////////
		// 
		// Band Properties
		//
		// ////////////////////////////////////////////////

		/** Number of bands */
		protected int bandsNumber;

		/** Array to store the maximum value for each band */
		protected Double[] maximums;

		/** Array to store the minimum value for each band */
		protected Double[] minimums;

		/** Array to store the noData value for each band */
		protected Double[] noDataValues;

		/** Array to store the scale value for each band */
		protected Double[] scales;

		/** Array to store the offset value for each band */
		protected Double[] offsets;

		/** Array to store the color interpretation for each band */
		protected int[] colorInterpretations;

		protected IIOMetadata iioMetadata;

		/**
		 * <code>GDALDatasetWrapper</code> constructor. Firstly, it provides
		 * to open a dataset from the specified input dataset name. Then, it
		 * call the constructor which initializes all fields with dataset
		 * properties, such as raster size, raster tiling properties,
		 * projection, and more.
		 * 
		 * @param sDatasetName
		 *            The name (usually a File path or a subdataset name when
		 *            the format supports subdatasets) of the dataset we want to
		 *            open.
		 */
		public GDALDatasetWrapper(String sDatasetName) {
			this(GDALUtilities.acquireDataSet(sDatasetName,
					gdalconst.GA_ReadOnly), sDatasetName);
		}
		
		public GDALDatasetWrapper(Dataset ds, String name, final boolean initializationRequired) {
			datasetName = name;
			datasetDescription = ds.GetDescription();
			driverDescription = ds.GetDriver().GetDescription();
			driverName = ds.GetDriver().getShortName();
			if (initializationRequired)
				setMembers(ds);
			setGeoreferencingInfo(ds);
			// clean up data set in order to avoid keeping them around for a lot
			// of time.
			GDALUtilities.closeDataSet(ds);
		}
		
		/**
		 * Constructor which initializes fields by retrieving properties such as
		 * raster size, raster tiling properties, projection, and more from a
		 * given input <code>Dataset</code>.
		 * 
		 * @param ds
		 *            the <code>Dataset</code> used to initialize all
		 *            {@link GDALDatasetWrapper}'s fields.
		 * @param name
		 *            the dataset name
		 */
		public GDALDatasetWrapper(Dataset ds, String name) {
			this (ds,name,true);
			
		}

		/**
		 * A kind of initialization method which provides to set all fields of
		 * the {@link GDALDatasetWrapper}
		 * 
		 * @param dataset
		 *            the <code>Dataset</code> which will be used for the
		 *            initialization
		 * @return <code>true</code> if the initialization was successfully
		 *         completed. <code>false</code> if some field wasn't properly
		 *         initialized
		 */
		private boolean setMembers(Dataset dataset) {
			// Retrieving raster properties
			width = dataset.getRasterXSize();
			height = dataset.getRasterYSize();

			// Retrieving block size
			final int[] xBlockSize = new int[1];
			final int[] yBlockSize = new int[1];

			// Remember: RasterBand numeration starts from 1
			dataset.GetRasterBand(1).GetBlockSize(xBlockSize, yBlockSize);
			tileHeight = yBlockSize[0];
			tileWidth = xBlockSize[0];
			if (GDALImageReader.this.spi.needsTilesTuning())
				performTileSizeTuning(dataset);

			// /////////////////////////////////////////////////////////////////
			//
			// Getting dataset main properties
			//
			// /////////////////////////////////////////////////////////////////
			bandsNumber = dataset.getRasterCount();
			if (bandsNumber <= 0)
				return false;
			final int xsize = dataset.getRasterXSize();
			final int ysize = dataset.getRasterYSize();

			// If the image is very big, its size expressed as the number of
			// bytes needed to store pixels, may be a negative number
			final int totalImageSize = xsize
					* ysize
					* bandsNumber
					* (gdal.GetDataTypeSize(dataset.GetRasterBand(1)
							.getDataType()) / 8);

			// bands variables
			final int[] banks = new int[bandsNumber];
			final int[] offsetsR = new int[bandsNumber];
			noDataValues = new Double[bandsNumber];
			scales = new Double[bandsNumber];
			offsets = new Double[bandsNumber];
			minimums = new Double[bandsNumber];
			maximums = new Double[bandsNumber];
			colorInterpretations = new int[bandsNumber];
			int buf_type = 0;

			Band pBand = null;

			// scanning bands
			final Double tempD[] = new Double[1];
			final int bandsOffset[] = new int[bandsNumber];
			for (int band = 0; band < bandsNumber; band++) {
				/* Bands are not 0-base indexed, so we must add 1 */
				pBand = dataset.GetRasterBand(band + 1);
				buf_type = pBand.getDataType();
				banks[band] = band;
				offsetsR[band] = 0;
				pBand.GetNoDataValue(tempD);
				noDataValues[band] = tempD[0];
				pBand.GetOffset(tempD);
				offsets[band] = tempD[0];
				pBand.GetScale(tempD);
				scales[band] = tempD[0];
				pBand.GetMinimum(tempD);
				minimums[band] = tempD[0];
				pBand.GetMaximum(tempD);
				maximums[band] = tempD[0];
				colorInterpretations[band] = pBand
						.GetRasterColorInterpretation();
				bandsOffset[band] = band;
			}

			// /////////////////////////////////////////////////////////////////
			//
			// Variable used to specify the data type for the storing samples
			// of the SampleModel
			//
			// /////////////////////////////////////////////////////////////////
			int buffer_type = 0;
			if (buf_type == gdalconstConstants.GDT_Byte)
				buffer_type = DataBuffer.TYPE_BYTE;
			else if (buf_type == gdalconstConstants.GDT_UInt16)
				buffer_type = DataBuffer.TYPE_USHORT;
			else if (buf_type == gdalconstConstants.GDT_Int16)
				buffer_type = DataBuffer.TYPE_SHORT;
			else if ((buf_type == gdalconstConstants.GDT_Int32)
					|| (buf_type == gdalconstConstants.GDT_UInt32))
				buffer_type = DataBuffer.TYPE_INT;
			else if (buf_type == gdalconstConstants.GDT_Float32)
				buffer_type = DataBuffer.TYPE_FLOAT;
			else if (buf_type == gdalconstConstants.GDT_Float64)
				buffer_type = DataBuffer.TYPE_DOUBLE;
			else
				return false;

			// //
			//
			// Setting the Sample Model
			//
			// //
			if (totalImageSize < 0)
				sampleModel = new BandedSampleModel(buffer_type, xsize, ysize,
						xsize, banks, offsetsR);
			else
				sampleModel = new PixelInterleavedSampleModel(buffer_type,
						xsize, ysize, bandsNumber, xsize * bandsNumber,
						bandsOffset);

			// //
			//
			// Setting the Color Model
			//
			// //
			if (pBand.GetRasterColorInterpretation() == gdalconstConstants.GCI_PaletteIndex) {
				colorModel = pBand.GetRasterColorTable().getIndexColorModel(
						gdal.GetDataTypeSize(buf_type));
			} else {
				ColorSpace cs = null;
				if (bandsNumber > 1) {
					// /////////////////////////////////////////////////////////////////
					//
					// Number of Bands > 1.
					// ImageUtil.createColorModel provides to Creates a
					// ColorModel that may be used with the specified
					// SampleModel
					//
					// /////////////////////////////////////////////////////////////////
					colorModel = ImageUtil.createColorModel(sampleModel);
					if (colorModel == null) {
						LOGGER.severe("No ColorModels found");
						return false;
					}

				} else if ((buffer_type == DataBuffer.TYPE_BYTE)
						|| (buffer_type == DataBuffer.TYPE_USHORT)
						|| (buffer_type == DataBuffer.TYPE_INT)
						|| (buffer_type == DataBuffer.TYPE_FLOAT)
						|| (buffer_type == DataBuffer.TYPE_DOUBLE)) {

					// Just one band. Using the built-in Gray Scale Color Space
					cs = ColorSpace.getInstance(ColorSpace.CS_GRAY);
					colorModel = RasterFactory.createComponentColorModel(
							buffer_type, // dataType
							cs, // color space
							false, // has alpha
							false, // is alphaPremultiplied
							Transparency.OPAQUE); // transparency
				} else {
					if (buffer_type == DataBuffer.TYPE_SHORT) {
						// Just one band. Using the built-in Gray Scale Color
						// Space
						cs = ColorSpace.getInstance(ColorSpace.CS_GRAY);
						colorModel = new ComponentColorModel(cs, false, false,
								Transparency.OPAQUE, DataBuffer.TYPE_SHORT);
					}
				}
			}
			if (colorModel == null || sampleModel == null)
				return false;
			return true;

		}

		/**
		 * Given an input <code>Dataset</code>, sets georeferencing
		 * information
		 * 
		 * @param dataset
		 *            a <code>Dataset</code> from where to retrieve all
		 *            georeferencing information available
		 */
		private void setGeoreferencingInfo(Dataset dataset) {
			// Setting CRS's related information
			dataset.GetGeoTransform(geoTransformation);
			projection = dataset.GetProjection();
			gcpProjection = dataset.GetGCPProjection();
			gcpNumber = dataset.GetGCPCount();

		}

		/**
		 * Provides to increase data access performances. In many cases, the
		 * block size for a raster band is a single line of N pixels, where N is
		 * the width of the raster.
		 * 
		 * The Java Advanced Imaging allows to load and manipulate data only
		 * when they are needed (The Deferred Execution Model). This is done by
		 * working only on tiles containing the required data.
		 * 
		 * However, reading a big image composed of tiles having a size of Nx1
		 * (The most commonly used block size) would be not optimized because
		 * for each tile, a read operation is computed (very small tiles -> very
		 * high read operations number)
		 * 
		 * In order to optimize data access operations, it would be better to
		 * make tiles a little bit greater than just a single line of pixels.
		 * 
		 * @param dataset
		 * 
		 */
		private void performTileSizeTuning(Dataset dataset) {
			final int width = dataset.getRasterXSize();
			final int height = dataset.getRasterYSize();
			final Dimension imageSize = new Dimension(width, height);
			final Dimension tileSize = GDALUtilities.toTileSize(imageSize);
			tileHeight = tileSize.height;
			tileWidth = tileSize.width;
		}

		// ////////////////////////////////////////////////////////////////////
		// Simple Set of getters
		// ////////////////////////////////////////////////////////////////////

		public final ColorModel getColorModel() {
			return colorModel;
		}

		public synchronized IIOMetadata getImageIOMetadata() {
			if (iioMetadata == null) {
				iioMetadata = getIIOImageMetadata(this);
			}
			return iioMetadata;
		}

		public final String getDatasetName() {
			return datasetName;
		}

		public final int getHeight() {
			return height;
		}

		public final SampleModel getSampleModel() {
			return sampleModel;
		}

		public final int getTileHeight() {
			return tileHeight;
		}

		public final int getTileWidth() {
			return tileWidth;
		}

		public final int getWidth() {
			return width;
		}

		public final double[] getGeoTransformation() {
			return geoTransformation;
		}

		public final int getBandsNumber() {
			return bandsNumber;
		}

		public final int getGcpNumber() {
			return gcpNumber;
		}

		public final Double[] getMaximums() {
			return maximums;
		}

		public final Double[] getMinimums() {
			return minimums;
		}

		public final Double[] getOffsets() {
			return offsets;
		}

		public final String getProjection() {
			return projection;
		}

		public final Double[] getScales() {
			return scales;
		}

		public String getGcpProjection() {
			return gcpProjection;
		}

		public final String getDrivername() {
			return driverName;
		}

		public synchronized final List getGcps() {
			if (gcps == null) {
				gcps = new Vector(gcpNumber);
				final Dataset ds = GDALUtilities.acquireDataSet(datasetName,
						gdalconst.GA_ReadOnly);
				ds.GetGCPs((Vector) gcps);
				GDALUtilities.closeDataSet(ds);
			}
			return Collections.unmodifiableList(gcps);

		}

		public final Double[] getNoDataValues() {
			return noDataValues;
		}

		public final String getDriverDescription() {
			return driverDescription;
		}

		public final String getDatasetDescription() {
			return datasetDescription;
		}

		public final int[] getColorInterpretations() {
			return colorInterpretations;
		}

		// //////////////////////////////////////////////////////////////////////////////////
		// 
		// End of GDALDatasetWrapper Class
		// 
		// //////////////////////////////////////////////////////////////////////////////////
	}

	/**
	 * Retrieves a {@link GDALDatasetWrapper} by index.
	 * 
	 * @param imageIndex
	 *            is the index of the required {@link GDALDatasetWrapper}.
	 * @return a {@link GDALDatasetWrapper}
	 */
	public GDALDatasetWrapper getDataSetWrapper(int imageIndex) {
		checkImageIndex(imageIndex);
		// getting dataset name
		final String datasetName = datasetNames[imageIndex];
		synchronized (datasetMap) {

			if (datasetMap.containsKey(datasetName))
				return ((GDALDatasetWrapper) datasetMap.get(datasetName));
			else {
				// Add a new GDALDatasetWrapper to the HashMap
				GDALDatasetWrapper wrapper = new GDALDatasetWrapper(datasetName);
				datasetMap.put(datasetName, wrapper);
				return wrapper;
			}
		}
	}

	protected abstract IIOMetadata getIIOImageMetadata(
			GDALDatasetWrapper wrapper);

	/**
	 * Constructs a
	 * <code>GDALImageReader<code> using a {@link GDALImageReaderSpi}.
	 * 
	 * @param originatingProvider
	 *            The {@link GDALImageReaderSpi} to use for building this
	 *            <code>GDALImageReader<code>.
	 */
	public GDALImageReader(GDALImageReaderSpi originatingProvider) {
		super(originatingProvider);
		spi = originatingProvider;
	}

	// /**
	// * Provides to return any metadata related to the specified image. The
	// * SUBDATASETS domain is not returned since it is related to the whole
	// * stream instead of a single image.
	// *
	// *
	// * @param imageIndex
	// * index of the specified image.
	// * @return a <code>Vector</code> containing any metadata found.
	// */
	// public List getGDALImageMetadata(int imageIndex) {
	// checkImageIndex(imageIndex);
	// final GDALDatasetWrapper item = getDataSet(imageIndex);
	// final Dataset ds = GDALUtilities.acquireDataSet(item.getDatasetName(),
	// gdalconst.GA_ReadOnly);
	// ds.GetMetadata_Dict("");
	// gdalImageMetadata = ds.GetMetadata_List("");
	// ds.delete();
	// return gdalImageMetadata;
	// }

	// /**
	// * Provides to return any metadata which is not related to a specific
	// image.
	// * The actual implementation provide to return only the SUBDATASETS domain
	// * metadata but it may be changed in future.
	// *
	// * @return a <code>Vector</code> containing metadata related to the
	// * stream.
	// */
	// public List getGDALStreamMetadata() {
	// if (nSubdatasets == 0)
	// gdalStreamMetadata = getGDALImageMetadata(0);
	// else {
	// checkImageIndex(nSubdatasets);
	// final GDALDatasetWrapper item = getDataSet(nSubdatasets);
	// final Dataset ds = GDALUtilities.acquireDataSet(item
	// .getDatasetName(), gdalconst.GA_ReadOnly);
	// gdalStreamMetadata = ds.GetMetadata_List("SUBDATASETS");
	// ds.delete();
	// }
	// return gdalStreamMetadata;
	//
	// }

	// /**
	// * Returns the value of a specific metadata item related to the stream. As
	// * an instance, it may be used to find the name or the description of a
	// * specific subdataset.
	// *
	// * @param metadataName
	// * the name of the specified metadata item.
	// * @return the value of the required metadata item.
	// */
	// public String getStreamMetadataItem(String metadataName) {
	// if (gdalStreamMetadata == null)
	// getGDALStreamMetadata();
	// return getMetadataItem(gdalStreamMetadata, metadataName);
	// }
	//
	// /**
	// * Provides to return the value of a specific metadata item contained in
	// the
	// * metadata parameter
	// *
	// * @param gdalImageMetadata
	// * the required metadata <code>Vector</code>
	// * (gdalStreamMetadata or gdalImageMetadata)
	// * @param metadataName
	// * the name of the specified metadata item
	// * @return the value of the specified metadata item
	// */
	// private String getMetadataItem(List imageMetadata, String metadataName) {
	// final Iterator it = imageMetadata.iterator();
	// // Metadata items scanning
	// while (it.hasNext()) {
	// String s = (String) it.next();
	// int indexOfEqualSymbol = s.indexOf('=');
	// String sName = s.substring(0, indexOfEqualSymbol);
	// if (sName.equals(metadataName))
	// return s.substring(indexOfEqualSymbol + 1, s.length());
	// }
	// return null;
	// }

	/**
	 * Checks if the specified ImageIndex is valid.
	 * 
	 * @param imageIndex
	 *            the specified imageIndex
	 * 
	 * @throws IndexOutOfBoundsException
	 *             if imageIndex is belower than 0 or if is greater than the
	 *             number of subdatasets contained within the source (when the
	 *             format supports subdatasets)
	 */
	protected void checkImageIndex(final int imageIndex) {
		initialize();
		final boolean isSupportingSubdatasets = spi.supportsSubdatasets();

		// ////////////////////////////////////////////////////////////////////
		// When is an imageIndex not valid? 1) When it is negative 2) When the
		// format does not support subdatasets and imageIndex is > 0 3) When the
		// format supports subdatasets, but there isn't any subdataset and
		// imageIndex is greater than zero. 4) When the format supports
		// subdatasets, there are N subdatasets but imageIndex exceeds the
		// subdatasets count.
		// 
		// It is worthwile to remark that in case of nSubdatasets > 0, the
		// mainDataset is stored in the last position of datasetNames array. In
		// such a case the max valid imageIndex is nSubdatasets.
		// ////////////////////////////////////////////////////////////////////

		if (imageIndex < 0
				|| (!isSupportingSubdatasets && imageIndex > 0)
				|| (isSupportingSubdatasets && ((nSubdatasets == 0 && imageIndex > 0) || (nSubdatasets != 0 && (imageIndex > nSubdatasets))))) {

			// The specified imageIndex is not valid.
			// Retrieving the valid image index range.
			final int validImageIndex = isSupportingSubdatasets ? nSubdatasets
					: 0;
			StringBuffer sb = new StringBuffer(
					"Illegal imageIndex specified = ").append(imageIndex)
					.append(", while the valid imageIndex");
			if (validImageIndex > 0)
				// There are N Subdatasets.
				sb.append(" range should be (0,").append(validImageIndex - 1)
						.append(")!!");
			else
				// Only the imageIndex 0 is valid.
				sb.append(" should be only 0!");
			throw new IndexOutOfBoundsException(sb.toString());
		}
	}

	/**
	 * Initializes the <code>GDALImageReader<code>
	 * 
	 * @return <code>true</code> if the source of this reader has several 
	 * subDatasets.
	 */
	private boolean initialize() {
		if (!GDALImageReaderSpi.isAvailable())
			throw new IllegalStateException(
					"GDAL native libraries are not available.");
		synchronized (datasetMap) {
			if (!isInitialized) {

				// Retrieving the fileName in order to open the main dataset
				final String mainDatasetFileName = getDatasetSource(
						super.getInput()).getAbsolutePath();
				final Dataset mainDataset = GDALUtilities.acquireDataSet(
						mainDatasetFileName, gdalconstConstants.GA_ReadOnly);
				if (mainDataset == null)
					return false;
				// /////////////////////////////////////////////////////////////
				//
				// Listing available subdatasets
				//
				// /////////////////////////////////////////////////////////////
				final List subdatasets = mainDataset
						.GetMetadata_List("SUBDATASETS");

				// /////////////////////////////////////////////////////////////
				//
				// setting the number of subdatasets
				// It is worth to remind that the subdatasets vector
				// contains both Subdataset's Name and Subdataset's Description
				// Thus we need to divide its size by two.
				//
				// /////////////////////////////////////////////////////////////
				nSubdatasets = subdatasets.size() / 2;

				// /////////////////////////////////////////////////////////////
				//
				// Some formats supporting subdatasets may have no
				// subdatasets.
				// As an instance, the HDF4ImageReader may read HDF4Images
				// which are single datasets containing no subdatasets.
				// Thus, theDataset is simply the main dataset.
				//
				// /////////////////////////////////////////////////////////////
				if (nSubdatasets == 0) {
					datasetNames = new String[1];
					datasetNames[0] = datasetSource.getAbsolutePath();
					final GDALDatasetWrapper myItem = createDataSetWrapper(datasetNames[0]);
					datasetMap.put(datasetNames[0], myItem);
				} else {
					datasetNames = new String[nSubdatasets + 1];
					for (int i = 0; i < nSubdatasets; i++) {
						final String subdatasetName = (subdatasets.get(i * 2))
								.toString();
						final int nameStartAt = subdatasetName
								.lastIndexOf("_NAME=") + 6;
						datasetNames[i] = subdatasetName.substring(nameStartAt);
					}
					datasetNames[nSubdatasets] = mainDatasetFileName;
					datasetMap.put(mainDatasetFileName, createDataSetWrapper(mainDataset, mainDatasetFileName));
					subdatasets.clear();
				}
				isInitialized = true;
				GDALUtilities.closeDataSet(mainDataset);
			}
		}
		return nSubdatasets > 0;
	}

	protected abstract GDALDatasetWrapper createDataSetWrapper(String string);

	protected abstract GDALDatasetWrapper createDataSetWrapper(
			Dataset mainDataset, String mainDatasetFileName);

	/**
	 * Provides to read data from the required region of the raster (the actual
	 * dataset)
	 * 
	 * @param item
	 *            a <code>GDALDatasetWrapper</code> related to the actual
	 *            dataset
	 * @param param
	 *            an <code>ImageReadParam</code> used to control the reading
	 *            process, or <code>null</code>.
	 * @return the read <code>Raster</code>
	 */
	private Raster readDatasetRaster(GDALDatasetWrapper item,
			ImageReadParam param) throws IOException {

		SampleModel sampleModel = null;
		DataBuffer imgBuffer = null;

		// ////////////////////////////////////////////////////////////////////
		//
		// -------------------------------------------------------------------
		// Raster Creation >>> Step 1: Initialization
		// -------------------------------------------------------------------
		//
		// ////////////////////////////////////////////////////////////////////

		final int width = item.getWidth();
		final int height = item.getHeight();
		final Dataset dataset = GDALUtilities.acquireDataSet(item
				.getDatasetName(), gdalconst.GA_ReadOnly);

		if (param == null)
			param = getDefaultReadParam();

		int dstWidth = -1;
		int dstHeight = -1;
		int srcRegionWidth = -1;
		int srcRegionHeight = -1;
		int srcRegionXOffset = -1;
		int srcRegionYOffset = -1;
		int xSubsamplingFactor = -1;
		int ySubsamplingFactor = -1;

		// //
		//
		// Retrieving Information about Source Region and doing
		// additional intialization operations.
		//
		// //
		Rectangle srcRegion = param.getSourceRegion();
		if (srcRegion != null) {
			srcRegionWidth = (int) srcRegion.getWidth();
			srcRegionHeight = (int) srcRegion.getHeight();
			srcRegionXOffset = (int) srcRegion.getX();
			srcRegionYOffset = (int) srcRegion.getY();

			// //
			//
			// Minimum correction for wrong source regions
			//
			// When you do subsampling or source subsetting it might happen that
			// the given source region in the read param is uncorrect, which
			// means it can be or a bit larger than the original file or can
			// begin a bit before original limits.
			//
			// We got to be prepared to handle such case in order to avoid
			// generating ArrayIndexOutOfBoundsException later in the code.
			//
			// //

			if (srcRegionXOffset < 0)
				srcRegionXOffset = 0;
			if (srcRegionYOffset < 0)
				srcRegionYOffset = 0;
			if ((srcRegionXOffset + srcRegionWidth) > width) {
				srcRegionWidth = width - srcRegionXOffset;
			}
			// initializing destWidth
			dstWidth = srcRegionWidth;

			if ((srcRegionYOffset + srcRegionHeight) > height) {
				srcRegionHeight = height - srcRegionYOffset;
			}
			// initializing dstHeight
			dstHeight = srcRegionHeight;

		} else {
			// Source Region not specified.
			// Assuming Source Region Dimension equal to Source Image Dimension
			dstWidth = width;
			dstHeight = height;
			srcRegionXOffset = srcRegionYOffset = 0;
			srcRegionWidth = width;
			srcRegionHeight = height;
		}

		// SubSampling variables initialization
		xSubsamplingFactor = param.getSourceXSubsampling();
		ySubsamplingFactor = param.getSourceYSubsampling();

		// ////
		//
		// Updating the destination size in compliance with
		// the subSampling parameters
		//
		// ////

		dstWidth = ((dstWidth - 1) / xSubsamplingFactor) + 1;
		dstHeight = ((dstHeight - 1) / ySubsamplingFactor) + 1;

		if ((xSubsamplingFactor > width) || (ySubsamplingFactor > height)) {
			GDALUtilities.closeDataSet(dataset);
			throw new IOException(
					"The subSamplingFactor cannot be greater than image size!");
		}

		// Band set-up
		Band pBand = null;

		// Getting number of bands
		// final int nBands = dataset.getRasterCount();
		final int nBands = item.getBandsNumber();

		int[] banks = new int[nBands];
		int[] offsets = new int[nBands];

		// setting the number of pixels to read
		final int pixels = dstWidth * dstHeight;
		int bufferType = 0, bufferSize = 0;

		// ////////////////////////////////////////////////////////////////////
		//
		// -------------------------------------------------------------------
		// Raster Creation >>> Step 2: Data Read
		// -------------------------------------------------------------------
		//
		// ////////////////////////////////////////////////////////////////////

		// NOTE: Bands are not 0-base indexed, so we must add 1
		pBand = dataset.GetRasterBand(1);

		// setting buffer properties
		bufferType = pBand.getDataType();
		final int typeSizeInBytes = gdal.GetDataTypeSize(bufferType) / 8;
		bufferSize = nBands * pixels * typeSizeInBytes;

		// splitBands = false -> I read n Bands at once.
		// splitBands = false -> I need to read 1 Band at a time.
		boolean splitBands = false;

		if (bufferSize < 0 || item.sampleModel instanceof BandedSampleModel) {
			// The number resulting from the product
			// "bandsNumber*pixels*gdal.GetDataTypeSize(buf_type) / 8"
			// may be negative (A very high number which is not
			// "int representable")
			// In such a case, we will read 1 band at a time.
			bufferSize = pixels * typeSizeInBytes;
			splitBands = true;
		}
		int dataBufferType = -1;
		ByteBuffer[] bands = new ByteBuffer[nBands];
		for (int k = 0; k < nBands; k++) {

			// If I'm reading n Bands at once and I performed the first read,
			// I quit the loop
			if (k > 0 && !splitBands)
				break;

			final ByteBuffer dataBuffer = ByteBuffer.allocateDirect(bufferSize);

			final int returnVal;
			if (!splitBands) {
				// // I can read bandsNumber at once.
				// returnVal = dataset.ReadRaster_Direct(srcRegionXOffset,
				// srcRegionYOffset, srcRegionWidth, srcRegionHeight,
				// dstWidth, dstHeight, bufferType, nBands, dataBuffer);
				// bands[k] = dataBuffer;
				// I can read bandsNumber at once.
				returnVal = dataset.ReadRaster_Direct(srcRegionXOffset,
						srcRegionYOffset, srcRegionWidth, srcRegionHeight,
						dstWidth, dstHeight, bufferType, nBands, nBands
								* typeSizeInBytes, dstWidth * nBands
								* typeSizeInBytes, typeSizeInBytes, dataBuffer);
				bands[k] = dataBuffer;

			} else {
				// I need to read 1 band at a time.
				returnVal = dataset.GetRasterBand(k + 1).ReadRaster_Direct(
						srcRegionXOffset, srcRegionYOffset, srcRegionWidth,
						srcRegionHeight, dstWidth, dstHeight, bufferType,
						dataBuffer);
				bands[k] = dataBuffer;
			}
			if (returnVal == gdalconstConstants.CE_None) {
				if (!splitBands)
					for (int band = 0; band < nBands; band++) {
						banks[band] = band;
						offsets[band] = band;
					}
				else {
					banks[k] = k;
					offsets[k] = 0;
				}
			} else {
				// The read operation was not successfully computed.
				// Showing error messages.
				LOGGER.info(new StringBuffer("Last error: ").append(
						gdal.GetLastErrorMsg()).toString());
				LOGGER.info(new StringBuffer("Last error number: ").append(
						gdal.GetLastErrorNo()).toString());
				LOGGER.info(new StringBuffer("Last error type: ").append(
						gdal.GetLastErrorType()).toString());
				GDALUtilities.closeDataSet(dataset);
				throw new RuntimeException(gdal.GetLastErrorMsg());
			}
		}

		// /////////////////////////////////////////////////////////////////////
		//
		// TYPE BYTE
		//
		// /////////////////////////////////////////////////////////////////////
		if (bufferType == gdalconstConstants.GDT_Byte) {
			if (!splitBands) {
				final byte[] bytes = new byte[nBands * pixels];
				bands[0].get(bytes, 0, nBands * pixels);
				imgBuffer = new DataBufferByte(bytes, nBands * pixels);
			} else {
				final byte[][] bytes = new byte[nBands][];
				for (int i = 0; i < nBands; i++) {
					bytes[i] = new byte[pixels];
					bands[i].get(bytes[i], 0, pixels);
				}
				imgBuffer = new DataBufferByte(bytes, pixels);
			}
			dataBufferType = DataBuffer.TYPE_BYTE;
		} else if (bufferType == gdalconstConstants.GDT_Int16
				|| bufferType == gdalconstConstants.GDT_UInt16) {
			// ////////////////////////////////////////////////////////////////
			//
			// TYPE SHORT
			//
			// ////////////////////////////////////////////////////////////////

			// short[][] shorts = new short[nBands][];

			if (!splitBands) {
				// I get short values from the ByteBuffer using a view
				// of the ByteBuffer as a ShortBuffer
				// It is worth to create the view outside the loop.
				short[] shorts = new short[nBands * pixels];
				bands[0].order(ByteOrder.nativeOrder());
				final ShortBuffer buff = bands[0].asShortBuffer();
				buff.get(shorts, 0, nBands * pixels);
				imgBuffer = new DataBufferShort(shorts, nBands * pixels);
			} else {
				short[][] shorts = new short[nBands][];
				for (int i = 0; i < nBands; i++) {
					shorts[i] = new short[pixels];
					bands[i].order(ByteOrder.nativeOrder());
					bands[i].asShortBuffer().get(shorts[i], 0, pixels);
				}
				imgBuffer = new DataBufferShort(shorts, pixels);
			}
			if (bufferType == gdalconstConstants.GDT_UInt16)
				dataBufferType = DataBuffer.TYPE_USHORT;
			else
				dataBufferType = DataBuffer.TYPE_SHORT;
		} else if (bufferType == gdalconstConstants.GDT_Int32
				|| bufferType == gdalconstConstants.GDT_UInt32) {
			// ////////////////////////////////////////////////////////////////
			//
			// TYPE INT
			//
			// ////////////////////////////////////////////////////////////////

			if (!splitBands) {
				// I get int values from the ByteBuffer using a view
				// of the ByteBuffer as an IntBuffer
				// It is worth to create the view outside the loop.
				int[] ints = new int[nBands * pixels];
				bands[0].order(ByteOrder.nativeOrder());
				final IntBuffer buff = bands[0].asIntBuffer();
				buff.get(ints, 0, nBands * pixels);
				imgBuffer = new DataBufferInt(ints, nBands * pixels);
			} else {
				int[][] ints = new int[nBands][];
				for (int i = 0; i < nBands; i++) {
					ints[i] = new int[pixels];
					bands[i].order(ByteOrder.nativeOrder());
					bands[i].asIntBuffer().get(ints[i], 0, pixels);
				}
				imgBuffer = new DataBufferInt(ints, pixels);
			}
			dataBufferType = DataBuffer.TYPE_INT;

		} else if (bufferType == gdalconstConstants.GDT_Float32) {
			// /////////////////////////////////////////////////////////////////////
			//
			// TYPE FLOAT
			//
			// /////////////////////////////////////////////////////////////////////

			if (!splitBands) {
				// I get float values from the ByteBuffer using a view
				// of the ByteBuffer as an FloatBuffer
				// It is worth to create the view outside the loop.
				float[] floats = new float[nBands * pixels];
				bands[0].order(ByteOrder.nativeOrder());
				final FloatBuffer buff = bands[0].asFloatBuffer();
				buff.get(floats, 0, nBands * pixels);
				imgBuffer = new DataBufferFloat(floats, nBands * pixels);
			} else {
				float[][] floats = new float[nBands][];
				for (int i = 0; i < nBands; i++) {
					floats[i] = new float[pixels];
					bands[i].order(ByteOrder.nativeOrder());
					bands[i].asFloatBuffer().get(floats[i], 0, pixels);
				}
				imgBuffer = new DataBufferFloat(floats, pixels);
			}
			dataBufferType = DataBuffer.TYPE_FLOAT;
		} else if (bufferType == gdalconstConstants.GDT_Float64) {
			// /////////////////////////////////////////////////////////////////////
			//
			// TYPE DOUBLE
			//
			// /////////////////////////////////////////////////////////////////////

			if (!splitBands) {
				// // I get double values from the ByteBuffer using a view
				// of the ByteBuffer as an DoubleBuffer
				// It is worth to create the view outside the loop.
				double[] doubles = new double[nBands * pixels];
				bands[0].order(ByteOrder.nativeOrder());
				final DoubleBuffer buff = bands[0].asDoubleBuffer();
				buff.get(doubles, 0, nBands * pixels);
				imgBuffer = new DataBufferDouble(doubles, nBands * pixels);
			} else {
				double[][] doubles = new double[nBands][];
				for (int i = 0; i < nBands; i++) {
					doubles[i] = new double[pixels];
					bands[i].order(ByteOrder.nativeOrder());
					bands[i].asDoubleBuffer().get(doubles[i], 0, pixels);
				}
				imgBuffer = new DataBufferDouble(doubles, pixels);
			}
			dataBufferType = DataBuffer.TYPE_DOUBLE;

		} else {
			// TODO: Handle other cases if needed.
			LOGGER.info("More cases need to be handled");
		}

		// ////////////////////////////////////////////////////////////////////
		//
		// -------------------------------------------------------------------
		// Raster Creation >>> Step 3: Setting DataBuffer
		// -------------------------------------------------------------------
		//
		// ////////////////////////////////////////////////////////////////////

		if (LOGGER.isLoggable(Level.FINE))
			LOGGER.fine((new Integer(GDALUtilities.getCacheUsed())).toString());

		// //
		// ////////////////////////////////////////////////////////////////////
		// //
		// //
		// -------------------------------------------------------------------
		// // Raster Creation >>> Step 4: Setting SampleModel
		// //
		// -------------------------------------------------------------------
		// //
		// //
		// ////////////////////////////////////////////////////////////////////
		if (splitBands)
			sampleModel = new BandedSampleModel(dataBufferType, dstWidth,
					dstHeight, dstWidth, banks, offsets);
		else
			sampleModel = new PixelInterleavedSampleModel(dataBufferType,
					dstWidth, dstHeight, nBands, dstWidth * nBands, offsets);

		// ////////////////////////////////////////////////////////////////////
		//
		// -------------------------------------------------------------------
		// Raster Creation >>> Final Step: Actual Raster Creation
		// -------------------------------------------------------------------
		//
		// ////////////////////////////////////////////////////////////////////
		GDALUtilities.closeDataSet(dataset);
		return Raster.createWritableRaster(sampleModel, imgBuffer, null);

	}

	/**
	 * Tries to retrieve the Dataset Source for the ImageReader's input.
	 */
	protected File getDatasetSource(Object myInput) {

		if (datasetSource == null) {
			if (myInput instanceof File)
				datasetSource = (File) myInput;
			else if (myInput instanceof FileImageInputStreamExt)
				datasetSource = ((FileImageInputStreamExt) myInput).getFile();
			else if (input instanceof URL) {
				final URL tempURL = (URL) input;
				if (tempURL.getProtocol().equalsIgnoreCase("file")) {
					try {
						datasetSource = new File(URLDecoder.decode(tempURL
								.getFile(), "UTF-8"));

					} catch (IOException e) {
						throw new RuntimeException("Not a Valid Input", e);
					}
				}
			} else
				// should never happen
				throw new RuntimeException(
						"Unable to retrieve the Data Source for"
								+ "the provided input");
		}
		return datasetSource;
	}

	/**
	 * Sets the input for the specialized reader.
	 */
	public void setInput(Object input, boolean seekForwardOnly,
			boolean ignoreMetadata) {
		if (LOGGER.isLoggable(Level.FINE))
			LOGGER.fine("Setting Input");

		// Prior to set a new input, I need to do a pre-emptive reset in order
		// to clear any value-object which was related to the previous input.
		if (this.imageInputStream != null) {
			reset();
			imageInputStream = null;
		}

		if (input == null)
			throw new NullPointerException("The provided input is null!");

		if (input instanceof File) {
			datasetSource = (File) input;
			try {

				imageInputStream = ImageIO.createImageInputStream(input);
			} catch (IOException e) {
				throw new RuntimeException("Not a Valid Input", e);
			}
		} else if (input instanceof FileImageInputStreamExt) {
			datasetSource = ((FileImageInputStreamExt) input).getFile();
			imageInputStream = (ImageInputStream) input;

		} else if (input instanceof URL) {
			final URL tempURL = (URL) input;
			if (tempURL.getProtocol().equalsIgnoreCase("file")) {

				try {
					datasetSource = new File(URLDecoder.decode(tempURL
							.getFile(), "UTF-8"));
					imageInputStream = ImageIO.createImageInputStream(input);
				} catch (IOException e) {
					throw new RuntimeException("Not a Valid Input", e);
				}
			}
		}

		// /////////////////////////////////////////////////////////////////////
		//
		// Checking if this input is of a supported format.
		// Now, I have an ImageInputStream and I can try to see if the input's
		// format is supported by the specialized reader
		//
		// /////////////////////////////////////////////////////////////////////
		boolean isInputDecodable = false;
		if (imageInputStream != null) {
			Dataset dataSet = GDALUtilities.acquireDataSet(datasetSource
					.getAbsolutePath(), gdalconstConstants.GA_ReadOnly);
			if (dataSet != null) {
				isInputDecodable = ((GDALImageReaderSpi) spi)
						.isDecodable(dataSet);
				GDALUtilities.closeDataSet(dataSet);
			} else
				isInputDecodable = false;
		}
		if (isInputDecodable)
			super.setInput(imageInputStream, seekForwardOnly, ignoreMetadata);
		else {
			StringBuffer sb = new StringBuffer();
			if (imageInputStream == null)
				sb.append(
						"Unable to create a valid ImageInputStream "
								+ "for the provided input:").append(
						GDALUtilities.NEWLINE).append(input.toString());
			else
				sb.append("The Provided input is not supported by this reader");
			throw new IllegalArgumentException(sb.toString());
		}
	}

	/**
	 * Allows resources to be released
	 */
	public void dispose() {
		// Closing imageInputStream
		if (imageInputStream != null)
			try {
				imageInputStream.close();
			} catch (IOException ioe) {

			}
		imageInputStream = null;

		synchronized (datasetMap) {
			// Cleaning HashMap
			datasetMap.clear();
		}

		datasetMap = null;
		datasetNames = null;

		if (gdalStreamMetadata != null) {
			gdalStreamMetadata.clear();
			gdalStreamMetadata = null;
		}
		if (gdalImageMetadata != null) {
			gdalImageMetadata.clear();
			gdalImageMetadata = null;
		}
		super.dispose();
	}

	/**
	 * Reset main values
	 */
	public void reset() {
		super.setInput(null, false, false);
		isInitialized = false;
		nSubdatasets = -1;
	}

	/**
	 * Returns an <code>Iterator</code> containing possible image types to
	 * which the given image may be decoded, in the form of
	 * <code>ImageTypeSpecifiers</code>s. At least one legal image type will
	 * be returned.
	 * 
	 * This method uses the <code>setSampleModelAndColorModel</code> method.
	 * 
	 * @param imageIndex
	 *            the index of the image to be retrieved.
	 * 
	 * 
	 * @return an <code>Iterator</code> containing possible image types to
	 *         which the given image may be decoded, in the form of
	 *         <code>ImageTypeSpecifiers</code>s
	 */

	public Iterator getImageTypes(int imageIndex) throws IOException {
		final List l = new java.util.ArrayList(5);
		final GDALDatasetWrapper item = getDataSetWrapper(imageIndex);
		imageType = new ImageTypeSpecifier(item.getColorModel(), item
				.getSampleModel());
		l.add(imageType);
		return l.iterator();
	}

	/**
	 * Read the raster and returns a <code>BufferedImage</code>
	 * 
	 * @param imageIndex
	 *            the index of the image to be retrieved.
	 * @param param
	 *            an <code>ImageReadParam</code> used to control the reading
	 *            process, or <code>null</code>.
	 * @return the desired portion of the image as a <code>BufferedImage</code>
	 */

	public BufferedImage read(int imageIndex, ImageReadParam param)
			throws IOException {
		// Retrieving the requested dataset
		final GDALDatasetWrapper item = getDataSetWrapper(imageIndex);

		// WritableRaster r=(WritableRaster) readDatasetRaster(item,
		// param == null ? getDefaultReadParam() : param);
		// BufferedImage oldImage = new BufferedImage(item.getColorModel(),
		// r, false,
		// null);
		//		
		// final BufferedImage rgb= new
		// BufferedImage(r.getWidth(),r.getHeight(),BufferedImage.TYPE_INT_RGB);
		// final Graphics2D g2d = (Graphics2D) rgb.getGraphics();
		// g2d.drawImage(oldImage, null,null);
		// g2d.dispose();
		// oldImage.flush();
		// oldImage=null;

		return new BufferedImage(item.getColorModel(),
				(WritableRaster) readDatasetRaster(item,
						param == null ? getDefaultReadParam() : param), false,
				null);

	}

	/**
	 * Implements the <code>ImageRead.readRaster</code> method which returns a
	 * new <code>Raster</code> object containing the raw pixel data from the
	 * image stream, without any color conversion applied.
	 * 
	 * @param imageIndex
	 *            the index of the image to be retrieved.
	 * @param param
	 *            an <code>ImageReadParam</code> used to control the reading
	 *            process, or <code>null</code>.
	 * @return the desired portion of the image as a <code>Raster</code>.
	 */
	public Raster readRaster(int imageIndex, ImageReadParam param)
			throws IOException {
		return readDatasetRaster(getDataSetWrapper(imageIndex), param);

	}

	/**
	 * Performs a full read operation.
	 * 
	 * @param imageIndex
	 *            the index of the image to be retrieved.
	 */
	public BufferedImage read(int imageIndex) throws IOException {
		if (LOGGER.isLoggable(Level.FINE))
			LOGGER.fine("read(imageIndex)");
		return read(imageIndex, null);

	}

	/**
	 * Returns the number of images (subdatasets) contained within the data
	 * source. If there are no subdatasets, it returns 1.
	 */
	public int getNumImages(boolean allowSearch) throws IOException {
		if (LOGGER.isLoggable(Level.FINE))
			LOGGER.fine("getting NumImages");
		// If Format supports subdatasets and the subdatasets list is not empty
		// returns the number of subdatasets.
		if (!spi.supportsSubdatasets())
			return 1;
		initialize();
		return nSubdatasets;
	}

	/**
	 * Returns the width of of the raster of the <code>Dataset</code> at index
	 * <code>imageIndex</code>.
	 * 
	 * @param imageIndex
	 *            the index of the specified raster
	 * @return raster width
	 */
	public int getWidth(int imageIndex) throws IOException {
		return getDataSetWrapper(imageIndex).getWidth();

	}

	/**
	 * Returns the height of the raster of the <code>Dataset</code> at index
	 * <code>imageIndex</code>.
	 * 
	 * @param imageIndex
	 *            the index of the specified raster
	 * @return raster height
	 */
	public int getHeight(int imageIndex) throws IOException {
		return getDataSetWrapper(imageIndex).getHeight();
	}

	/**
	 * Returns the tile height of the raster of the <code>Dataset</code> at
	 * index <code>imageIndex</code>.
	 * 
	 * @param imageIndex
	 *            the index of the specified raster
	 * @return raster tile height
	 */

	public int getTileHeight(int imageIndex) throws IOException {
		return getDataSetWrapper(imageIndex).getTileHeight();
	}

	/**
	 * Returns the tile width of the raster of the <code>Dataset</code> at
	 * index <code>imageIndex</code>.
	 * 
	 * @param imageIndex
	 *            the index of the specified raster
	 * @return raster tile width
	 */

	public int getTileWidth(int imageIndex) throws IOException {
		return getDataSetWrapper(imageIndex).getTileWidth();
	}

	// ////////////////////////////////////////////////////////////////////////
	//
	// CRS Retrieval METHODS
	//
	// ///////////////////////////////////////////////////////////////////////

	/**
	 * Retrieves the WKT projection <code>String</code> for the
	 * <code>Dataset</code> at index <code>imageIndex</code>.
	 * 
	 * @param imageIndex
	 *            is the index of the dataset we want to get the projections
	 *            for.
	 * @return the WKT projection <code>String</code> for the
	 *         <code>Dataset</code> at index <code>imageIndex</code>.
	 */
	public String getProjection(final int imageIndex) {
		return getDataSetWrapper(imageIndex).getProjection();
	}

	/**
	 * Retrieves the GeoTransformation coefficients for the <code>Dataset</code>
	 * at index <code>imageIndex</code>.
	 * 
	 * @param imageIndex
	 *            is the index of the dataset we want to get the coefficients
	 *            for.
	 * @return the array containing the GeoTransformation coefficients.
	 */
	public double[] getGeoTransform(final int imageIndex) {
		return getDataSetWrapper(imageIndex).getGeoTransformation();
	}

	/**
	 * Returns Ground Control Points of the <code>Dataset</code> at index
	 * <code>imageIndex</code>.
	 * 
	 * @param imageIndex
	 *            the index of the specified <code>Dataset</code>
	 * 
	 * @return a <code>List</code> containing the Ground Control Points.
	 * 
	 */
	public List getGCPs(final int imageIndex) {
		return getDataSetWrapper(imageIndex).getGcps();
	}

	/**
	 * Returns the Ground Control Points projection definition string of the
	 * <code>Dataset</code> at index <code>imageIndex</code>.
	 * 
	 * @param imageIndex
	 *            the index of the specified <code>Dataset</code>
	 * 
	 * @return the Ground Control Points projection definition string.
	 */
	public String getGCPProjection(final int imageIndex) {
		return getDataSetWrapper(imageIndex).getGcpProjection();
	}

	/**
	 * Returns the number of Ground Control Points of the <code>Dataset</code>
	 * at index imageIndex.
	 * 
	 * @param imageIndex
	 *            the index of the specified <code>Dataset</code>
	 * 
	 * @return the number of GroundControlPoints of the <code>Dataset</code>.
	 */
	public int getGCPCount(final int imageIndex) {
		return getDataSetWrapper(imageIndex).getGcpNumber();
	}

	// ///////////////////////////////////////////////////////////////////
	//
	// Raster Band Properties Retrieval METHODS
	//
	// TODO: Why returning always property[0] instead of property[band]??
	//
	// ///////////////////////////////////////////////////////////////////

	/**
	 * Returns the NoDataValue of the specified Band of the specified image
	 * 
	 * @param imageIndex
	 *            the specified image
	 * @param band
	 *            the specified band
	 * @return the Band NoDataValue if available, <code>Double.NaN</code>
	 *         otherwise.
	 */

	public double getNoDataValue(int imageIndex, int band) {
		final GDALDatasetWrapper item = getDataSetWrapper(imageIndex);
		if (band > item.bandsNumber)
			throw new IllegalArgumentException("Invalid band number");
		final Double[] noDataValue = item.noDataValues;
		if (noDataValue != null && noDataValue[band] != null)
			return noDataValue[band].doubleValue();
		throw new IllegalArgumentException();
	}

	/**
	 * Returns the optional Offset Value of the specified band of the
	 * <code>Dataset</code> at index <code>imageIndex</code>.
	 * 
	 * @param imageIndex
	 *            the index of the specified <code>Dataset</code>
	 * @param band
	 *            the specified band
	 * @return the Band Offset Value if available, <code>Double.NaN</code>
	 *         otherwise.
	 */
	public double getOffset(int imageIndex, int band) {
		final GDALDatasetWrapper item = getDataSetWrapper(imageIndex);
		if (band > item.bandsNumber)
			throw new IllegalArgumentException("Invalid band number");
		final Double[] offset = item.offsets;
		if (offset != null && offset[band] != null)
			return offset[band].doubleValue();
		throw new IllegalArgumentException();

	}

	/**
	 * Returns the optional Scale Value of the specified band of the
	 * <code>Dataset</code> at index <code>imageIndex</code>.
	 * 
	 * @param imageIndex
	 *            the index of the specified <code>Dataset</code>
	 * @param band
	 *            the specified band
	 * @return the Band Scale Value if available, <code>Double.NaN</code>
	 *         otherwise.
	 */
	public double getScale(int imageIndex, int band) {
		final GDALDatasetWrapper item = getDataSetWrapper(imageIndex);
		if (band > item.bandsNumber)
			throw new IllegalArgumentException("Invalid band number");
		final Double[] scale = item.scales;
		if (scale != null && scale[band] != null)
			return scale[band].doubleValue();
		throw new IllegalArgumentException();
	}

	/**
	 * Returns the optional Minimum Value of the specified band of the
	 * <code>Dataset</code> at index <code>imageIndex</code>.
	 * 
	 * @param imageIndex
	 *            the index of the specified <code>Dataset</code>
	 * @param band
	 *            the specified band
	 * @return the Band Minimum Value if available, <code>Double.NaN</code>
	 *         otherwise.
	 */
	public double getMinimum(int imageIndex, int band) {
		final GDALDatasetWrapper item = getDataSetWrapper(imageIndex);
		if (band > item.bandsNumber)
			throw new IllegalArgumentException("Invalid band number");
		final Double[] minimums = item.minimums;
		if (minimums != null && minimums[band] != null)
			return minimums[band].doubleValue();
		throw new IllegalArgumentException();
	}

	/**
	 * Returns the optional Maximum Value of the specified band of the
	 * <code>Dataset</code> at index <code>imageIndex</code>.
	 * 
	 * @param imageIndex
	 *            the index of the specified <code>Dataset</code>
	 * @param band
	 *            the specified band
	 * @return the Band Maximum Value if available, <code>Double.NaN</code>
	 *         otherwise.
	 */
	public double getMaximum(int imageIndex, int band) {
		final GDALDatasetWrapper item = getDataSetWrapper(imageIndex);
		if (band > item.bandsNumber)
			throw new IllegalArgumentException("Invalid band number");
		final Double[] maximums = item.maximums;
		if (maximums != null && maximums[band] != null)
			return maximums[band].doubleValue();
		throw new IllegalArgumentException();
	}

	public Dataset getLastRecentlyUsedDataset() {
		synchronized (datasetMap) {
			Set keys = datasetMap.keySet();
			GDALDatasetWrapper myItem = (GDALDatasetWrapper) datasetMap
					.get(keys.iterator().next());
			if (myItem != null)
				return GDALUtilities.acquireDataSet(myItem.datasetName,
						gdalconst.GA_ReadOnly);
		}
		return null;

	}

	public IIOMetadata getStreamMetadata() throws IOException {
		initialize();
		return new GDALCommonIIOStreamMetadata(datasetNames);
	}

}