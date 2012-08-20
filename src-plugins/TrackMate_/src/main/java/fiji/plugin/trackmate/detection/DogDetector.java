package fiji.plugin.trackmate.detection;

import java.util.ArrayList;
import java.util.List;

import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.legacy.scalespace.DifferenceOfGaussian;
import net.imglib2.algorithm.legacy.scalespace.DifferenceOfGaussianPeak;
import net.imglib2.algorithm.legacy.scalespace.SubpixelLocalization;
import net.imglib2.exception.IncompatibleTypeException;
import net.imglib2.img.Img;
import net.imglib2.img.ImgFactory;
import net.imglib2.img.ImgPlus;
import net.imglib2.outofbounds.OutOfBoundsFactory;
import net.imglib2.outofbounds.OutOfBoundsMirrorExpWindowingFactory;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.SpotImp;
import fiji.plugin.trackmate.util.TMUtils;

public class DogDetector <T extends RealType<T>  & NativeType<T>> extends AbstractSpotDetector<T> {

	/*
	 * FIELDS
	 */
	
	public final static String BASE_ERROR_MESSAGE = "DogDetector: ";
	public static final String NAME = "DoG detector";
	public static final String INFO_TEXT = "<html>" +
			"This segmenter is based on an approximation of the LoG operator <br> " +
			"by differences of gaussian (DoG). Computations are made in direct space. <br>" +
			"It is the quickest for small spot sizes (< ~5 pixels). " +
			"<p> " +
			"Spots found too close are suppressed. This segmenter can do sub-pixel <br>" +
			"localization of spots using a quadratic fitting scheme. It is based on <br>" +
			"the scale-space framework made by Stephan Preibisch for ImgLib. " +
			"</html>";	
	private LogDetectorSettings<T> settings;
	
	/*
	 * CONSTRUCTOR
	 */
	
	public DogDetector() {
		this.baseErrorMessage = BASE_ERROR_MESSAGE;
	}
	

	/*
	 * METHODS
	 */
	
	public SpotDetector<T> createNewDetector() {
		return new DogDetector<T>();
	};
	
	@Override
	public void setTarget(ImgPlus<T> image, DetectorSettings<T> settings) {
		super.setTarget(image, settings);
		this.settings = (LogDetectorSettings<T>) settings;
	}

	@Override
	public boolean process() {

		// Deal with median filter:
		Img<T> intermediateImage = img;
		if (settings.useMedianFilter) {
			intermediateImage = applyMedianFilter(intermediateImage);
			if (null == intermediateImage) {
				return false;
			}
		}
		
		final double[] calibration = TMUtils.getSpatialCalibration(img);
		final double radius = settings.expectedRadius;
		final float minPeakValue = settings.threshold;
		
		// First we need an image factory for FloatType
		ImgFactory<FloatType> imageFactory;
		try {
			imageFactory = img.factory().imgFactory(new FloatType());
		} catch (IncompatibleTypeException e) {
			errorMessage = BASE_ERROR_MESSAGE + "Could not instantiate image factory.";
			return false;
		}
		
		// And the out of bounds strategies for both types. It needs to be a value-oobs, with a constant
		// value of 0; otherwise, we will miss maxima on the border of the image.
		final OutOfBoundsFactory<FloatType, RandomAccessibleInterval<FloatType>> oobs2 = new OutOfBoundsMirrorExpWindowingFactory<FloatType, RandomAccessibleInterval<FloatType>>();
		
		double[] sigma1 = new double[img.numDimensions()];
		double[] sigma2 = new double[img.numDimensions()];
		for (int i = 0; i < sigma2.length; i++) {
			sigma1[i] = 2 / (1+Math.sqrt(img.numDimensions())) *  radius / calibration[i]; // in pixel units 
			sigma2[i] = Math.sqrt(img.numDimensions()) * sigma1[i];
		}
		
		final DifferenceOfGaussian<T> dog = new DifferenceOfGaussian<T>(intermediateImage, imageFactory, oobs2, sigma1, sigma2, minPeakValue, 1.0);
		/* The DogDetector class will be called in a multi-threaded way, so the DifferenceOfGaussianRealNI
		 * does not need to be multi-threaded. On top of that, reports from users on win32 platform 
		 * indicate that multi-threading generates some silent problems, with some frames (first ones
		 * being not present in the final SpotColleciton. 
		 * On 64-bit platforms, I could see that keeping the DogRNI multi-threaded translated by a 
		 * speedup of about 10%, which I sacrifice without hesitation if i can make the plugin more stable. */
		dog.setNumThreads(1);
		
		// Keep laplace image if needed
		if (settings.doSubPixelLocalization)
			dog.setKeepDoGImg(true);
		
		// Execute
		if ( !dog.checkInput() || !dog.process() )	{
			errorMessage = baseErrorMessage + dog.getErrorMessage();
			return false;
		}
				
		// Get all peaks
		List<DifferenceOfGaussianPeak<FloatType>> list = dog.getPeaks();
		RandomAccess<T> cursor = img.randomAccess();
		
		// Prune non-relevant peaks
		List<DifferenceOfGaussianPeak<FloatType>> pruned_list = new ArrayList<DifferenceOfGaussianPeak<FloatType>>();
		for(DifferenceOfGaussianPeak<FloatType> dogpeak : list) {
			if ( (dogpeak.getPeakType() != DifferenceOfGaussian.SpecialPoint.MAX))
				continue;
			cursor.setPosition(dogpeak);
			if (cursor.get().getRealFloat() < settings.threshold)
				continue;
			
			pruned_list.add(dogpeak);
		}
		
		// Deal with sub-pixel localization if required
		if (settings.doSubPixelLocalization && pruned_list.size() > 0) {
			Img<FloatType> laplacian = dog.getDoGImg();
			SubpixelLocalization<FloatType> locator = new SubpixelLocalization<FloatType>(laplacian , pruned_list);
			locator.setNumThreads(1); // Since the calls to a segmenter  are already multi-threaded.
			if ( !locator.checkInput() || !locator.process() )	{
				errorMessage = baseErrorMessage + locator.getErrorMessage();
				return false;
			}
			pruned_list = locator.getDoGPeaks();
		}

		// Create spots
		spots.clear();
		for(DifferenceOfGaussianPeak<FloatType> dogpeak : pruned_list) {
			double[] coords = new double[3];
			if (settings.doSubPixelLocalization) {
				for (int i = 0; i < img.numDimensions(); i++) 
					coords[i] = dogpeak.getSubPixelPosition(i) * calibration[i];
			} else {
				for (int i = 0; i < img.numDimensions(); i++) 
					coords[i] = dogpeak.getDoublePosition(i) * calibration[i];
			}
			Spot spot = new SpotImp(coords);
			spot.putFeature(Spot.QUALITY, -dogpeak.getValue().get());
			spot.putFeature(Spot.RADIUS, settings.expectedRadius);
			spots.add(spot);
		}
		
		// Prune overlapping spots
		spots = TMUtils.suppressSpots(spots, Spot.QUALITY);
		
		return true;
	}
	
	@Override
	public String toString() {
		return NAME;
	}
	
	@Override
	public String getInfoText() {
		return INFO_TEXT;
	}

}
