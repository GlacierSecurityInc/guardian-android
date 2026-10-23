package com.glaciersecurity.glaciermessenger.ui.adapter;

import static com.glaciersecurity.glaciermessenger.testutil.ViewHarness.layoutAt;
import static com.glaciersecurity.glaciermessenger.testutil.ViewHarness.onMain;
import static com.glaciersecurity.glaciermessenger.testutil.ViewHarness.themedContext;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.glaciersecurity.glaciermessenger.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Security Hub list, bound through a real {@link RecyclerView}.
 *
 * <p>The Security Hub is the screen that tells someone whether their device is safe, and every one
 * of its rows is a claim about that. A row bound to the wrong item, a chevron that says a row can
 * be expanded when it cannot, or a collapsed row that shows the expanded text are all wrong
 * answers delivered confidently -- no error, just a security screen saying something untrue.
 *
 * <p>It is bound through an actual RecyclerView rather than by calling {@code onBindViewHolder}
 * directly, and that is not ceremony: the bind body reads {@code holder.getAdapterPosition()}
 * rather than the {@code position} it was passed, which is {@code NO_POSITION} for a holder that
 * no RecyclerView owns. Calling it directly throws, so a test that did would be testing its own
 * setup.
 *
 * <p>This tier because RecyclerView measures, lays out and recycles for real, and because binding
 * inflates {@code item_expand} and resolves drawables from the app's theme.
 */
@RunWith(AndroidJUnit4.class)
public class SecurityHubListAdapterTest {

    private static final int WIDTH = 720;
    private static final int HEIGHT = 1280;

    private static ExpandableListItem collapsedExpandable(final String name) {
        return new ExpandableListItem(R.drawable.box_dns, name, "short description", true, false)
                .withExpandedDescription("the much longer description shown once expanded");
    }

    private static ExpandableListItem plain(final String name) {
        return new ExpandableListItem(R.drawable.box_dns, name, "short description");
    }

    /** A laid-out RecyclerView holding {@code items}, so the adapter's rows are really bound. */
    private static RecyclerView boundList(
            final Context context,
            final SecurityHubListAdapter adapter) {
        final RecyclerView list = new RecyclerView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(adapter);
        layoutAt(list, WIDTH, HEIGHT);
        return list;
    }

    private static View row(final RecyclerView list, final int index) {
        return list.getLayoutManager().getChildAt(index);
    }

    @Test
    public void everyItemGetsARow() {
        onMain(
                () -> {
                    final Context context = themedContext();
                    final List<ExpandableListItem> items =
                            new ArrayList<>(
                                    Arrays.asList(plain("One"), plain("Two"), plain("Three")));
                    final SecurityHubListAdapter adapter =
                            new SecurityHubListAdapter(context, items);
                    final RecyclerView list = boundList(context, adapter);

                    assertThat(adapter.getItemCount()).isEqualTo(3);
                    assertThat(list.getLayoutManager().getChildCount()).isEqualTo(3);
                });
    }

    @Test
    public void aRowShowsItsOwnNameAndDescription() {
        onMain(
                () -> {
                    final Context context = themedContext();
                    final List<ExpandableListItem> items =
                            new ArrayList<>(Arrays.asList(plain("First check"), plain("Second check")));
                    final RecyclerView list =
                            boundList(context, new SecurityHubListAdapter(context, items));

                    assertThat(name(row(list, 0))).isEqualTo("First check");
                    assertThat(name(row(list, 1))).isEqualTo("Second check");
                    assertThat(description(row(list, 0))).isEqualTo("short description");
                });
    }

    /**
     * A collapsed row shows the short text clipped to two lines; expanding swaps in the long text
     * and removes the limit. Losing the swap leaves the Hub permanently showing summaries with no
     * way to read the detail -- and the chevron still there, suggesting there is more.
     */
    @Test
    public void expandingARowSwapsInTheLongerDescription() {
        onMain(
                () -> {
                    final Context context = themedContext();
                    final ExpandableListItem item = collapsedExpandable("DNS");
                    final List<ExpandableListItem> items =
                            new ArrayList<>(java.util.Collections.singletonList(item));
                    final SecurityHubListAdapter adapter =
                            new SecurityHubListAdapter(context, items);
                    final RecyclerView list = boundList(context, adapter);

                    assertThat(description(row(list, 0))).isEqualTo("short description");
                    assertThat(descriptionView(row(list, 0)).getMaxLines()).isEqualTo(2);

                    item.isExpanded = true;
                    adapter.notifyItemChanged(0);
                    layoutAt(list, WIDTH, HEIGHT);

                    assertThat(description(row(list, 0)))
                            .isEqualTo("the much longer description shown once expanded");
                    assertThat(descriptionView(row(list, 0)).getMaxLines())
                            .isEqualTo(Integer.MAX_VALUE);
                });
    }

    @Test
    public void onlyAnExpandableRowShowsTheChevron() {
        onMain(
                () -> {
                    final Context context = themedContext();
                    final List<ExpandableListItem> items =
                            new ArrayList<>(
                                    Arrays.asList(collapsedExpandable("DNS"), plain("Static")));
                    final RecyclerView list =
                            boundList(context, new SecurityHubListAdapter(context, items));

                    assertThat(chevron(row(list, 0)).getVisibility()).isEqualTo(View.VISIBLE);
                    assertThat(chevron(row(list, 1)).getVisibility()).isEqualTo(View.INVISIBLE);
                });
    }

    /** The chevron's rotation is the only thing distinguishing an expanded row from a closed one. */
    @Test
    public void theChevronTurnsWhenTheRowIsExpanded() {
        onMain(
                () -> {
                    final Context context = themedContext();
                    final ExpandableListItem item = collapsedExpandable("DNS");
                    final List<ExpandableListItem> items =
                            new ArrayList<>(java.util.Collections.singletonList(item));
                    final SecurityHubListAdapter adapter =
                            new SecurityHubListAdapter(context, items);
                    final RecyclerView list = boundList(context, adapter);

                    assertThat(chevron(row(list, 0)).getRotation()).isEqualTo(0f);

                    item.isExpanded = true;
                    adapter.notifyItemChanged(0);
                    layoutAt(list, WIDTH, HEIGHT);

                    assertThat(chevron(row(list, 0)).getRotation()).isEqualTo(90f);
                });
    }

    /**
     * The click has to carry the item that was actually tapped. A row bound to one item and
     * reporting another is exactly the failure recycling causes, and it is invisible -- the wrong
     * detail simply opens.
     */
    @Test
    public void tappingARowReportsThatRowsItemAndPosition() {
        onMain(
                () -> {
                    final Context context = themedContext();
                    final List<ExpandableListItem> items =
                            new ArrayList<>(
                                    Arrays.asList(plain("One"), plain("Two"), plain("Three")));
                    final SecurityHubListAdapter adapter =
                            new SecurityHubListAdapter(context, items);
                    final AtomicReference<ExpandableListItem> clicked = new AtomicReference<>();
                    final AtomicInteger clickedPosition = new AtomicInteger(-1);
                    adapter.setOnItemClickListener(
                            (obj, position) -> {
                                clicked.set(obj);
                                clickedPosition.set(position);
                            });
                    final RecyclerView list = boundList(context, adapter);

                    row(list, 1).findViewById(R.id.lyt_parent).performClick();

                    assertThat(clicked.get()).isNotNull();
                    assertThat(clicked.get().name).isEqualTo("Two");
                    assertThat(clickedPosition.get()).isEqualTo(1);
                });
    }

    @Test
    public void aRowWithNoListenerAttachedIsStillClickableWithoutThrowing() {
        onMain(
                () -> {
                    final Context context = themedContext();
                    final List<ExpandableListItem> items =
                            new ArrayList<>(java.util.Collections.singletonList(plain("One")));
                    final RecyclerView list =
                            boundList(context, new SecurityHubListAdapter(context, items));
                    row(list, 0).findViewById(R.id.lyt_parent).performClick();
                });
    }

    @Test
    public void replacingTheListRebindsEveryRow() {
        onMain(
                () -> {
                    final Context context = themedContext();
                    final SecurityHubListAdapter adapter =
                            new SecurityHubListAdapter(
                                    context,
                                    new ArrayList<>(
                                            java.util.Collections.singletonList(plain("Before"))));
                    final RecyclerView list = boundList(context, adapter);
                    assertThat(name(row(list, 0))).isEqualTo("Before");

                    adapter.updateSechubList(
                            new ArrayList<>(Arrays.asList(plain("After"), plain("Also after"))));
                    layoutAt(list, WIDTH, HEIGHT);

                    assertThat(adapter.getItemCount()).isEqualTo(2);
                    assertThat(name(row(list, 0))).isEqualTo("After");
                    assertThat(name(row(list, 1))).isEqualTo("Also after");
                });
    }

    /**
     * {@code updateCoreConnect} finds the row to replace by {@code ExpandableListItem.equals},
     * which compares the name and nothing else. That is load-bearing and easy to break by
     * "fixing" equals to compare more fields -- the status would then never update, and the Hub
     * would keep showing the Core connection as it was when the screen opened.
     */
    @Test
    public void theCoreConnectionRowIsFoundByNameAndUpdatedInPlace() {
        onMain(
                () -> {
                    final Context context = themedContext();
                    final String coreConnect = context.getString(R.string.core_connect);
                    final List<ExpandableListItem> items =
                            new ArrayList<>(
                                    Arrays.asList(
                                            plain("Unrelated"),
                                            new ExpandableListItem(
                                                    R.drawable.key_vpn_disabled,
                                                    coreConnect,
                                                    context.getString(
                                                            R.string.core_connect_disabled),
                                                    true,
                                                    false)));
                    final SecurityHubListAdapter adapter =
                            new SecurityHubListAdapter(context, items);
                    final RecyclerView list = boundList(context, adapter);

                    adapter.updateCoreConnect(true);
                    layoutAt(list, WIDTH, HEIGHT);

                    assertThat(adapter.getItemCount()).isEqualTo(2);
                    assertThat(name(row(list, 1))).isEqualTo(coreConnect);
                    assertThat(description(row(list, 1)))
                            .isEqualTo(context.getString(R.string.core_connect_enabled));
                });
    }

    @Test
    public void anUpdateForARowThatIsNotInTheListChangesNothing() {
        onMain(
                () -> {
                    final Context context = themedContext();
                    final List<ExpandableListItem> items =
                            new ArrayList<>(java.util.Collections.singletonList(plain("Unrelated")));
                    final SecurityHubListAdapter adapter =
                            new SecurityHubListAdapter(context, items);
                    final RecyclerView list = boundList(context, adapter);

                    adapter.updateCoreConnect(true);
                    adapter.updateDns(true);
                    layoutAt(list, WIDTH, HEIGHT);

                    assertThat(adapter.getItemCount()).isEqualTo(1);
                    assertThat(name(row(list, 0))).isEqualTo("Unrelated");
                });
    }

    @Test
    public void anEmptyHubHasNoRows() {
        onMain(
                () -> {
                    final Context context = themedContext();
                    final SecurityHubListAdapter adapter =
                            new SecurityHubListAdapter(context, new ArrayList<>());
                    final RecyclerView list = boundList(context, adapter);
                    assertThat(adapter.getItemCount()).isEqualTo(0);
                    assertThat(list.getLayoutManager().getChildCount()).isEqualTo(0);
                });
    }

    private static String name(final View row) {
        return ((TextView) row.findViewById(R.id.name)).getText().toString();
    }

    private static TextView descriptionView(final View row) {
        return row.findViewById(R.id.description);
    }

    private static String description(final View row) {
        return descriptionView(row).getText().toString();
    }

    private static ImageView chevron(final View row) {
        return row.findViewById(R.id.bt_expand);
    }
}
